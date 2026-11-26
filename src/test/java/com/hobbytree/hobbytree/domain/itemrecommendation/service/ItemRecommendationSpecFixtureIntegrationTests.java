package com.hobbytree.hobbytree.domain.itemrecommendation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.hobbytree.hobbytree.domain.activity.entity.Activity;
import com.hobbytree.hobbytree.domain.activity.entity.ActivityMaterial;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityMaterialRepository;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityRepository;
import com.hobbytree.hobbytree.domain.itemrecommendation.ItemRecommendationBoundaryFixtures;
import com.hobbytree.hobbytree.domain.itemrecommendation.client.ItemRecommendationAiClient;
import com.hobbytree.hobbytree.domain.itemrecommendation.client.ItemRecommendationAiProperties;
import com.hobbytree.hobbytree.domain.itemrecommendation.client.ItemRecommendationAiRequest;
import com.hobbytree.hobbytree.domain.itemrecommendation.controller.ItemRecommendationController;
import com.hobbytree.hobbytree.domain.itemrecommendation.dto.ItemRecommendationRequest;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.exception.GeneralExceptionAdvice;
import com.hobbytree.hobbytree.global.ratelimit.RateLimiter;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Feeds the AI team's own published example payloads from 준비물추천 API Spec.md (sections 2, 3, 5, 6.2,
 * 6.3 - copied verbatim, not re-derived) through the real snapshot + HTTP client code, instead of
 * hand-written fixtures. A pass here means this adapter's assumptions have not drifted from the
 * documented FE<->BE<->AI contract, independent of whether the AI PR is merged yet.
 */
class ItemRecommendationSpecFixtureIntegrationTests {
  private final ObjectMapper mapper = new ObjectMapper();
  private final UserRepository users = mock(UserRepository.class);
  private final ActivityRepository activities = mock(ActivityRepository.class);
  private final ActivityMaterialRepository materials = mock(ActivityMaterialRepository.class);
  private final ItemRecommendationSnapshot snapshot =
      new ItemRecommendationSnapshot(users, activities, materials);

  private HttpServer aiServer;
  private final AtomicReference<String> receivedByAi = new AtomicReference<>();
  private String aiResponseBody;

  // 2절 "제안 요청 예시"
  private static final String FIRST_PAGE_BE_REQUEST =
      """
      {
        "activityContentVersion": 3,
        "materialSelections": [
          {
            "materialId": 91001,
            "budgetKrw": 10000,
            "offset": 0,
            "catalogRevision": null,
            "filters": {
              "merchantCodes": [],
              "minPriceKrw": null,
              "maxPriceKrw": null,
              "excludeOfferRefs": []
            }
          }
        ],
        "maxProductsPerMaterial": 1
      }
      """;

  // 3절 "전체 내부 요청 예시" - the documented AI request this BE request must translate into.
  private static final String FIRST_PAGE_AI_REQUEST =
      """
      {
        "activityId": 9001,
        "activityContentVersion": 3,
        "reviewStatus": "PUBLISHED",
        "materials": [
          {
            "materialId": 91001,
            "name": "계량스푼",
            "quantity": "1개",
            "isRequired": true,
            "acquisitionType": null
          }
        ],
        "materialSelections": [
          {
            "materialId": 91001,
            "budgetKrw": 10000,
            "offset": 0,
            "catalogRevision": null,
            "filters": {
              "merchantCodes": [],
              "minPriceKrw": null,
              "maxPriceKrw": null,
              "excludeOfferRefs": []
            }
          }
        ],
        "maxProductsPerMaterial": 1
      }
      """;

  // 5절 "전체 내부 응답 예시" (envelope kept verbatim; this is what the AI client must parse).
  private static final String FIRST_PAGE_AI_RESPONSE =
      """
      {
        "isSuccess": true,
        "code": "ITEM_RECOMMENDATIONS_PROCESSED",
        "message": "저장된 준비물별 상품을 가격순으로 조회했습니다.",
        "result": {
          "activityId": 9001,
          "activityContentVersion": 3,
          "preferencesVersion": 0,
          "generatedAt": "2026-11-20T09:00:00+09:00",
          "status": "AVAILABLE",
          "onboardingFilteringApplied": false,
          "sort": "PRICE_ASC_NULLS_LAST",
          "materialResults": [
            {
              "material": {
                "materialId": 91001,
                "name": "계량스푼",
                "quantity": "1개",
                "isRequired": true,
                "acquisitionType": null
              },
              "status": "AVAILABLE",
              "providerStatus": "SUCCESS",
              "products": [
                {
                  "offerRef": "example_offer_001",
                  "provider": "ADPICK",
                  "providerProductId": null,
                  "title": "예시 계량스푼",
                  "displayPrice": "5,900원",
                  "priceKrw": 5900,
                  "currency": "KRW",
                  "imageUrl": null,
                  "purchaseUrl": "https://link.adpick.co.kr/example-only",
                  "merchantCode": "EXAMPLE_MERCHANT",
                  "merchantName": "예시 판매처",
                  "fetchedAt": "2026-11-20T03:30:00+09:00",
                  "priceIsReference": true,
                  "availability": "UNKNOWN",
                  "budgetComparison": {
                    "status": "WITHIN_BUDGET",
                    "budgetKrw": 10000,
                    "differenceKrw": -4100,
                    "message": "설정한 예산보다 4,100원 낮아요.",
                    "basis": "DISPLAYED_PRODUCT_PRICE_EXCLUDING_UNCONFIRMED_SHIPPING_AND_OPTIONS"
                  }
                }
              ],
              "necessityReason": null,
              "selectionSource": "PRIMARY",
              "catalogRevision": "example-catalog-revision-001",
              "expiresAt": "2026-11-21T03:30:00+09:00",
              "lastAttemptAt": "2026-11-20T03:30:00+09:00",
              "displayMessage": "낮은 가격순으로 보여드려요.",
              "fallbackAction": "NONE",
              "totalCandidates": 3,
              "nextOffset": 1,
              "discardedCount": 0,
              "discardReasons": {}
            }
          ],
          "affiliateDisclosure": "이 링크를 통한 구매 시 하비트리는 일정액의 수수료를 제공받을 수 있습니다.",
          "notices": [
            "가격은 수집 시점의 참고 가격입니다. 최종 가격·배송비·옵션·재고는 판매처에서 확인해주세요.",
            "예산은 현재 상품 1개의 표시 가격과 비교하며, 예산을 초과해도 상품을 제외하지 않습니다."
          ]
        }
      }
      """;

  // 6.2절 "다음 목록 요청·응답 예시"의 result.materialResults[0]을 그대로 가져와, 문서가 명시한 대로
  // ("전체 응답 wrapper는 5절과 같습니다") 5절 envelope에 끼워 넣은 전체 응답. displayPrice/priceKrw/
  // differenceKrw/message를 전부 8,900원·예산 10,000원 기준(-1,100원 미달)으로 일관되게 맞춘다.
  private static final String NEXT_PAGE_AI_RESPONSE =
      """
      {
        "isSuccess": true,
        "code": "ITEM_RECOMMENDATIONS_PROCESSED",
        "message": "저장된 준비물별 상품을 가격순으로 조회했습니다.",
        "result": {
          "activityId": 9001,
          "activityContentVersion": 3,
          "preferencesVersion": 0,
          "generatedAt": "2026-11-20T09:00:00+09:00",
          "status": "AVAILABLE",
          "onboardingFilteringApplied": false,
          "sort": "PRICE_ASC_NULLS_LAST",
          "materialResults": [
            {
              "material": {
                "materialId": 91001,
                "name": "계량스푼",
                "quantity": "1개",
                "isRequired": true,
                "acquisitionType": null
              },
              "status": "AVAILABLE",
              "providerStatus": "SUCCESS",
              "products": [
                {
                  "offerRef": "example_offer_002",
                  "provider": "ADPICK",
                  "providerProductId": null,
                  "title": "예시 계량스푼 B",
                  "displayPrice": "8,900원",
                  "priceKrw": 8900,
                  "currency": "KRW",
                  "imageUrl": null,
                  "purchaseUrl": "https://link.adpick.co.kr/example-only-002",
                  "merchantCode": "EXAMPLE_MERCHANT",
                  "merchantName": "예시 판매처",
                  "fetchedAt": "2026-11-20T03:30:00+09:00",
                  "priceIsReference": true,
                  "availability": "UNKNOWN",
                  "budgetComparison": {
                    "status": "WITHIN_BUDGET",
                    "budgetKrw": 10000,
                    "differenceKrw": -1100,
                    "message": "설정한 예산보다 1,100원 낮아요.",
                    "basis": "DISPLAYED_PRODUCT_PRICE_EXCLUDING_UNCONFIRMED_SHIPPING_AND_OPTIONS"
                  }
                }
              ],
              "necessityReason": null,
              "selectionSource": "PRIMARY",
              "catalogRevision": "example-catalog-revision-001",
              "expiresAt": "2026-11-21T03:30:00+09:00",
              "lastAttemptAt": "2026-11-20T03:30:00+09:00",
              "displayMessage": "낮은 가격순으로 보여드려요.",
              "fallbackAction": "NONE",
              "totalCandidates": 3,
              "nextOffset": 2,
              "discardedCount": 0,
              "discardReasons": {}
            }
          ],
          "affiliateDisclosure": "이 링크를 통한 구매 시 하비트리는 일정액의 수수료를 제공받을 수 있습니다.",
          "notices": [
            "가격은 수집 시점의 참고 가격입니다. 최종 가격·배송비·옵션·재고는 판매처에서 확인해주세요.",
            "예산은 현재 상품 1개의 표시 가격과 비교하며, 예산을 초과해도 상품을 제외하지 않습니다."
          ]
        }
      }
      """;

  // 6.2절 "다음 목록 요청" - nextOffset/catalogRevision carried over from the first page.
  private static final String NEXT_PAGE_BE_REQUEST =
      """
      {
        "activityContentVersion": 3,
        "materialSelections": [
          {
            "materialId": 91001,
            "budgetKrw": 10000,
            "offset": 1,
            "catalogRevision": "example-catalog-revision-001",
            "filters": {
              "merchantCodes": [],
              "minPriceKrw": null,
              "maxPriceKrw": null,
              "excludeOfferRefs": []
            }
          }
        ],
        "maxProductsPerMaterial": 1
      }
      """;

  // 6.3절 "준비물별 상품 필터 요청·응답 예시"의 result.materialResults[0]을 그대로 가져와 5절 envelope에
  // 끼워 넣은 전체 응답. displayPrice/priceKrw/differenceKrw/message를 전부 12,900원·예산 10,000원
  // 기준(2,900원 초과)으로 일관되게 맞춘다.
  private static final String FILTER_AI_RESPONSE =
      """
      {
        "isSuccess": true,
        "code": "ITEM_RECOMMENDATIONS_PROCESSED",
        "message": "저장된 준비물별 상품을 가격순으로 조회했습니다.",
        "result": {
          "activityId": 9001,
          "activityContentVersion": 3,
          "preferencesVersion": 0,
          "generatedAt": "2026-11-20T09:00:00+09:00",
          "status": "AVAILABLE",
          "onboardingFilteringApplied": false,
          "sort": "PRICE_ASC_NULLS_LAST",
          "materialResults": [
            {
              "material": {
                "materialId": 91001,
                "name": "계량스푼",
                "quantity": "1개",
                "isRequired": true,
                "acquisitionType": null
              },
              "status": "AVAILABLE",
              "providerStatus": "SUCCESS",
              "products": [
                {
                  "offerRef": "example_offer_003",
                  "provider": "ADPICK",
                  "providerProductId": null,
                  "title": "예시 계량스푼 C",
                  "displayPrice": "12,900원",
                  "priceKrw": 12900,
                  "currency": "KRW",
                  "imageUrl": null,
                  "purchaseUrl": "https://link.adpick.co.kr/example-only-003",
                  "merchantCode": "EXAMPLE_MERCHANT",
                  "merchantName": "예시 판매처",
                  "fetchedAt": "2026-11-20T03:30:00+09:00",
                  "priceIsReference": true,
                  "availability": "UNKNOWN",
                  "budgetComparison": {
                    "status": "OVER_BUDGET",
                    "budgetKrw": 10000,
                    "differenceKrw": 2900,
                    "message": "설정한 예산보다 2,900원 높아요.",
                    "basis": "DISPLAYED_PRODUCT_PRICE_EXCLUDING_UNCONFIRMED_SHIPPING_AND_OPTIONS"
                  }
                }
              ],
              "necessityReason": null,
              "selectionSource": "PRIMARY",
              "catalogRevision": "example-catalog-revision-001",
              "expiresAt": "2026-11-21T03:30:00+09:00",
              "lastAttemptAt": "2026-11-20T03:30:00+09:00",
              "displayMessage": "낮은 가격순으로 보여드려요.",
              "fallbackAction": "NONE",
              "totalCandidates": 1,
              "nextOffset": null,
              "discardedCount": 0,
              "discardReasons": {}
            }
          ],
          "affiliateDisclosure": "이 링크를 통한 구매 시 하비트리는 일정액의 수수료를 제공받을 수 있습니다.",
          "notices": [
            "가격은 수집 시점의 참고 가격입니다. 최종 가격·배송비·옵션·재고는 판매처에서 확인해주세요.",
            "예산은 현재 상품 1개의 표시 가격과 비교하며, 예산을 초과해도 상품을 제외하지 않습니다."
          ]
        }
      }
      """;

  // 6.3절 "준비물별 상품 필터 요청" - price filter satisfied, budget still exceeded.
  private static final String FILTER_BE_REQUEST =
      """
      {
        "activityContentVersion": 3,
        "materialSelections": [
          {
            "materialId": 91001,
            "budgetKrw": 10000,
            "offset": 0,
            "catalogRevision": null,
            "filters": {
              "merchantCodes": ["EXAMPLE_MERCHANT"],
              "minPriceKrw": 10000,
              "maxPriceKrw": 15000,
              "excludeOfferRefs": []
            }
          }
        ],
        "maxProductsPerMaterial": 1
      }
      """;

  @BeforeEach
  void seedActivity9001Version3AndStartAiStub() throws Exception {
    var user = mock(User.class);
    when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
    when(users.findById(1L)).thenReturn(Optional.of(user));
    var activity = mock(Activity.class);
    when(activity.getContentVersion()).thenReturn(3L);
    when(activities.findPublicDetail(9001L)).thenReturn(Optional.of(activity));
    var material = mock(ActivityMaterial.class);
    when(material.getActivityMaterialId()).thenReturn(91001L);
    when(material.getMaterialName()).thenReturn("계량스푼");
    when(material.getQuantityDescription()).thenReturn("1개");
    when(material.getIsRequired()).thenReturn(true);
    when(material.getAcquisitionType()).thenReturn(null);
    when(materials.findByActivity_ActivityIdOrderByActivityMaterialIdAsc(9001L))
        .thenReturn(List.of(material));

    aiServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    aiServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    aiServer.createContext(
        "/internal/v1/item-recommendations",
        exchange -> {
          receivedByAi.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] bytes = aiResponseBody.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    aiServer.start();
  }

  @AfterEach
  void stopAiStub() {
    SecurityContextHolder.clearContext();
    aiServer.stop(0);
  }

  private MockMvc mvc() {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(1L, "test-only", List.of()));
    return standaloneSetup(
            new ItemRecommendationController(
                new ItemRecommendationService(snapshot, client(), mock(RateLimiter.class))))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .setControllerAdvice(new GeneralExceptionAdvice())
        .build();
  }

  @Test
  void invalidOrOversizeAiResponsesReturnOpaquePublic502() throws Exception {
    var mvc = mvc();
    var tooMany =
        ItemRecommendationBoundaryFixtures.response(
            ItemRecommendationBoundaryFixtures.request(1, 1), 2);
    // Trailing JSON whitespace is legal: this is only one byte over the transport limit.
    String oversized =
        FIRST_PAGE_AI_RESPONSE
            + " ".repeat(262145 - FIRST_PAGE_AI_RESPONSE.getBytes(StandardCharsets.UTF_8).length);
    for (String body :
        List.of(mapper.writeValueAsString(tooMany), oversized, "private-diagnostic")) {
      aiResponseBody = body;
      var response =
          mvc.perform(
                  post("/api/v1/activities/9001/item-recommendations")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(FIRST_PAGE_BE_REQUEST))
              .andExpect(status().isBadGateway())
              .andExpect(jsonPath("$.isSuccess").value(false))
              .andExpect(jsonPath("$.code").value("ITEMREC502_1"))
              .andExpect(jsonPath("$.result").isEmpty())
              .andReturn()
              .getResponse();
      assertThat(response.getContentAsString()).doesNotContain("private-diagnostic", "synthetic_");
    }
  }

  @Test
  void nullFilterEntriesReturnPublic400WithoutAiRequest() throws Exception {
    var mvc = mvc();
    for (String field : List.of("merchantCodes", "excludeOfferRefs")) {
      String body =
          """
          {"activityContentVersion":3,"materialSelections":[
            {"materialId":91001,"filters":{"%s":[null]}}
          ]}
          """
              .formatted(field);
      mvc.perform(
              post("/api/v1/activities/9001/item-recommendations")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.isSuccess").value(false))
          .andExpect(jsonPath("$.code").value("ITEMREC400_1"))
          .andExpect(jsonPath("$.result").isEmpty());
    }
    assertThat(receivedByAi.get()).isNull();
  }

  @Test
  void oversizedCombinedRequestReturnsPublic400WithoutAiRequest() throws Exception {
    var rows = new ArrayList<ActivityMaterial>();
    var selections = new ArrayList<ItemRecommendationRequest.MaterialSelection>();
    for (long id = 91001; id < 91021; id++) {
      var row = mock(ActivityMaterial.class);
      when(row.getActivityMaterialId()).thenReturn(id);
      when(row.getMaterialName()).thenReturn("계량스푼");
      when(row.getIsRequired()).thenReturn(true);
      rows.add(row);
      selections.add(
          new ItemRecommendationRequest.MaterialSelection(
              id,
              null,
              0,
              null,
              new ItemRecommendationRequest.Filters(
                  List.of(), null, null, Collections.nCopies(100, "a".repeat(100)))));
    }
    when(materials.findByActivity_ActivityIdOrderByActivityMaterialIdAsc(9001L)).thenReturn(rows);
    var request = new ItemRecommendationRequest(3L, selections, 3);
    assertThat(mapper.writeValueAsBytes(snapshot.read(1L, 9001L, request)).length)
        .isGreaterThan(65536);

    mvc()
        .perform(
            post("/api/v1/activities/9001/item-recommendations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(request)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.isSuccess").value(false))
        .andExpect(jsonPath("$.code").value("ITEMREC400_1"))
        .andExpect(jsonPath("$.result").isEmpty());
    assertThat(receivedByAi.get()).isNull();
  }

  @Test
  void omittedNullAndEmptyFilterListsKeepSuccessfulPublicContract() throws Exception {
    var mvc = mvc();
    aiResponseBody = FIRST_PAGE_AI_RESPONSE;
    for (String filters :
        List.of(
            "",
            ",\"filters\":null",
            ",\"filters\":{}",
            ",\"filters\":{\"merchantCodes\":null,\"excludeOfferRefs\":null}",
            ",\"filters\":{\"merchantCodes\":[],\"excludeOfferRefs\":[]}")) {
      mvc.perform(
              post("/api/v1/activities/9001/item-recommendations")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"activityContentVersion\":3,\"materialSelections\":[{\"materialId\":91001"
                          + filters
                          + "}]}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.code").value("ITEMREC200_1"));
      var sent = mapper.readValue(receivedByAi.get(), ItemRecommendationAiRequest.class);
      assertThat(sent.materialSelections().get(0).filters().merchantCodes()).isEmpty();
      assertThat(sent.materialSelections().get(0).filters().excludeOfferRefs()).isEmpty();
      assertThat(sent.materialSelections().get(0).catalogRevision()).isNull();
    }
  }

  private ItemRecommendationAiClient client() {
    return new ItemRecommendationAiClient(
        new ItemRecommendationAiProperties(
            true,
            URI.create("http://127.0.0.1:" + aiServer.getAddress().getPort()),
            "isolated-test-token",
            Duration.ofSeconds(1),
            Duration.ofSeconds(2)),
        mapper);
  }

  @Test
  void firstPageSpecExampleRoundTripsThroughRealSnapshotAndClient() {
    var feRequest = mapper.readValue(FIRST_PAGE_BE_REQUEST, ItemRecommendationRequest.class);
    var built = snapshot.read(1L, 9001L, feRequest);
    var documented = mapper.readValue(FIRST_PAGE_AI_REQUEST, ItemRecommendationAiRequest.class);
    assertThat(built).isEqualTo(documented);

    aiResponseBody = FIRST_PAGE_AI_RESPONSE;
    var result = client().recommend(built);
    assertThat(mapper.readValue(receivedByAi.get(), ItemRecommendationAiRequest.class))
        .isEqualTo(documented);
    assertThat(result.materialResults().get(0).products().get(0).priceKrw()).isEqualTo(5900L);
    assertThat(result.materialResults().get(0).products().get(0).budgetComparison().status())
        .isEqualTo("WITHIN_BUDGET");
    assertThat(result.materialResults().get(0).nextOffset()).isEqualTo(1);
  }

  @Test
  void nextPageSpecExampleCarriesOverOffsetAndRevisionUnchanged() {
    var feRequest = mapper.readValue(NEXT_PAGE_BE_REQUEST, ItemRecommendationRequest.class);
    var built = snapshot.read(1L, 9001L, feRequest);

    assertThat(built.materialSelections().get(0).offset()).isEqualTo(1);
    assertThat(built.materialSelections().get(0).catalogRevision())
        .isEqualTo("example-catalog-revision-001");
    assertThat(built.materialSelections().get(0).budgetKrw()).isEqualTo(10000L);

    aiResponseBody = NEXT_PAGE_AI_RESPONSE;
    var result = client().recommend(built);
    var product = result.materialResults().get(0).products().get(0);
    assertThat(product.priceKrw()).isEqualTo(8900L);
    assertThat(product.displayPrice()).isEqualTo("8,900원");
    assertThat(product.budgetComparison().status()).isEqualTo("WITHIN_BUDGET");
    assertThat(product.budgetComparison().differenceKrw()).isEqualTo(-1100L);
    assertThat(product.budgetComparison().message()).isEqualTo("설정한 예산보다 1,100원 낮아요.");
    assertThat(result.materialResults().get(0).nextOffset()).isEqualTo(2);
  }

  @Test
  void filterSpecExampleKeepsOverBudgetProductVisibleWithCorrectDifference() {
    var feRequest = mapper.readValue(FILTER_BE_REQUEST, ItemRecommendationRequest.class);
    var built = snapshot.read(1L, 9001L, feRequest);

    assertThat(built.materialSelections().get(0).filters().merchantCodes())
        .containsExactly("EXAMPLE_MERCHANT");
    assertThat(built.materialSelections().get(0).filters().minPriceKrw()).isEqualTo(10000L);
    assertThat(built.materialSelections().get(0).filters().maxPriceKrw()).isEqualTo(15000L);

    // 가격 필터는 만족하지만 예산(10,000원)은 초과한 12,900원 상품이 제외되지 않고 유지된다.
    aiResponseBody = FILTER_AI_RESPONSE;
    var result = client().recommend(built);
    var product = result.materialResults().get(0).products().get(0);
    assertThat(product.priceKrw()).isEqualTo(12900L);
    assertThat(product.displayPrice()).isEqualTo("12,900원");
    var comparison = product.budgetComparison();
    assertThat(comparison.status()).isEqualTo("OVER_BUDGET");
    assertThat(comparison.differenceKrw()).isEqualTo(2900L);
    assertThat(comparison.message()).isEqualTo("설정한 예산보다 2,900원 높아요.");
    assertThat(result.materialResults().get(0).nextOffset()).isNull();
  }
}
