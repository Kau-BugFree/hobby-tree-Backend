package com.hobbytree.hobbytree.domain.itemrecommendation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.activity.code.ActivityErrorCode;
import com.hobbytree.hobbytree.domain.activity.entity.Activity;
import com.hobbytree.hobbytree.domain.activity.entity.ActivityMaterial;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityMaterialRepository;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityRepository;
import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.itemrecommendation.code.ItemRecommendationErrorCode;
import com.hobbytree.hobbytree.domain.itemrecommendation.dto.ItemRecommendationRequest;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ItemRecommendationSnapshotTests {
  private final UserRepository users = mock(UserRepository.class);
  private final ActivityRepository activities = mock(ActivityRepository.class);
  private final ActivityMaterialRepository materials = mock(ActivityMaterialRepository.class);
  private final ItemRecommendationSnapshot snapshot =
      new ItemRecommendationSnapshot(users, activities, materials);

  private User activeUser(long id) {
    var user = mock(User.class);
    when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
    when(users.findById(id)).thenReturn(Optional.of(user));
    return user;
  }

  private Activity activity(long version) {
    var activity = mock(Activity.class);
    when(activity.getContentVersion()).thenReturn(version);
    when(activities.findPublicDetail(9001L)).thenReturn(Optional.of(activity));
    return activity;
  }

  private ActivityMaterial material(long id, String name) {
    var m = mock(ActivityMaterial.class);
    when(m.getActivityMaterialId()).thenReturn(id);
    when(m.getMaterialName()).thenReturn(name);
    when(m.getQuantityDescription()).thenReturn("1개");
    when(m.getIsRequired()).thenReturn(true);
    when(m.getAcquisitionType()).thenReturn(null);
    return m;
  }

  // Builds every ActivityMaterial mock first, then stubs the repository in a separate statement -
  // calling material(...) inside a when(...).thenReturn(...) argument would start a nested
  // when()/thenReturn() cycle before the outer one closes, which Mockito rejects.
  private void stubMaterials(List<ActivityMaterial> rows) {
    when(materials.findByActivity_ActivityIdOrderByActivityMaterialIdAsc(9001L)).thenReturn(rows);
  }

  @Test
  void buildsAiRequestFromDbMaterialsIgnoringFeSelectionsForUnrelatedMaterials() {
    activeUser(1L);
    activity(3L);
    stubMaterials(List.of(material(91001L, "계량스푼")));
    var request =
        new ItemRecommendationRequest(
            3L,
            List.of(
                new ItemRecommendationRequest.MaterialSelection(
                    91001L,
                    10000L,
                    0,
                    null,
                    new ItemRecommendationRequest.Filters(
                        List.of("M1"), 1000L, 20000L, List.of()))),
            2);

    var ai = snapshot.read(1L, 9001L, request);

    assertThat(ai.activityId()).isEqualTo(9001L);
    assertThat(ai.activityContentVersion()).isEqualTo(3L);
    assertThat(ai.reviewStatus()).isEqualTo("PUBLISHED");
    assertThat(ai.materials()).hasSize(1);
    assertThat(ai.materials().get(0).materialId()).isEqualTo(91001L);
    assertThat(ai.materials().get(0).name()).isEqualTo("계량스푼");
    assertThat(ai.materialSelections()).hasSize(1);
    assertThat(ai.materialSelections().get(0).budgetKrw()).isEqualTo(10000L);
    assertThat(ai.materialSelections().get(0).filters().merchantCodes()).containsExactly("M1");
    assertThat(ai.maxProductsPerMaterial()).isEqualTo(2);
  }

  // BE-11/BE-12: offset/catalogRevision/filters pass through verbatim per material, and one
  // material's budget/filter choice never leaks into another material's selection.
  @Test
  void passesPaginationAndFiltersThroughPerMaterialWithoutCrossContamination() {
    activeUser(1L);
    activity(3L);
    stubMaterials(List.of(material(91001L, "계량스푼"), material(91002L, "냄비")));
    var request =
        new ItemRecommendationRequest(
            3L,
            List.of(
                new ItemRecommendationRequest.MaterialSelection(
                    91001L,
                    10000L,
                    1,
                    "catalog-rev-1",
                    new ItemRecommendationRequest.Filters(
                        List.of(), null, null, List.of("seen-1"))),
                new ItemRecommendationRequest.MaterialSelection(
                    91002L,
                    null,
                    0,
                    null,
                    new ItemRecommendationRequest.Filters(
                        List.of("MERCHANT_A"), 5000L, 15000L, List.of()))),
            null);

    var ai = snapshot.read(1L, 9001L, request);

    var first =
        ai.materialSelections().stream()
            .filter(s -> s.materialId().equals(91001L))
            .findFirst()
            .orElseThrow();
    var second =
        ai.materialSelections().stream()
            .filter(s -> s.materialId().equals(91002L))
            .findFirst()
            .orElseThrow();
    assertThat(first.budgetKrw()).isEqualTo(10000L);
    assertThat(first.offset()).isEqualTo(1);
    assertThat(first.catalogRevision()).isEqualTo("catalog-rev-1");
    assertThat(first.filters().excludeOfferRefs()).containsExactly("seen-1");
    // first material's budget/exclude list must not bleed into the second material's selection
    assertThat(first.filters().merchantCodes()).isEmpty();
    assertThat(first.filters().minPriceKrw()).isNull();
    assertThat(first.filters().maxPriceKrw()).isNull();
    assertThat(second.budgetKrw()).isNull();
    assertThat(second.offset()).isZero();
    assertThat(second.catalogRevision()).isNull();
    assertThat(second.filters().merchantCodes()).containsExactly("MERCHANT_A");
    assertThat(second.filters().minPriceKrw()).isEqualTo(5000L);
    assertThat(second.filters().maxPriceKrw()).isEqualTo(15000L);
    assertThat(second.filters().excludeOfferRefs()).isEmpty();
    // budgetKrw is a comparison input only - it must never be folded into the price filter range.
    assertThat(first.budgetKrw()).isNotEqualTo(first.filters().minPriceKrw());
  }

  @Test
  void defaultsMaxProductsPerMaterialAndEmptySelectionsWhenOmitted() {
    activeUser(1L);
    activity(3L);
    stubMaterials(List.of(material(91001L, "계량스푼")));

    var ai = snapshot.read(1L, 9001L, new ItemRecommendationRequest(3L, null, null));

    assertThat(ai.maxProductsPerMaterial()).isEqualTo(3);
    assertThat(ai.materialSelections()).isEmpty();
  }

  @Test
  void rejectsMissingActivityOrPrivateHobby() {
    activeUser(1L);
    when(activities.findPublicDetail(9001L)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () -> snapshot.read(1L, 9001L, new ItemRecommendationRequest(3L, null, null)))
        .isInstanceOfSatisfying(
            ProjectException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ActivityErrorCode.NOT_FOUND));
  }

  @Test
  void rejectsStaleOrMissingActivityContentVersion() {
    activeUser(1L);
    activity(4L);
    for (Long sent : java.util.Arrays.asList(3L, null)) {
      assertThatThrownBy(
              () -> snapshot.read(1L, 9001L, new ItemRecommendationRequest(sent, null, null)))
          .isInstanceOfSatisfying(
              ProjectException.class,
              e ->
                  assertThat(e.getErrorCode())
                      .isEqualTo(ItemRecommendationErrorCode.CONTENT_VERSION_CHANGED));
    }
  }

  @Test
  void rejectsActivitiesWithMoreThanTwentyMaterials() {
    activeUser(1L);
    activity(3L);
    List<ActivityMaterial> rows =
        java.util.stream.LongStream.range(0, 21).mapToObj(i -> material(i, "준비물" + i)).toList();
    stubMaterials(rows);
    assertThatThrownBy(
            () -> snapshot.read(1L, 9001L, new ItemRecommendationRequest(3L, null, null)))
        .isInstanceOfSatisfying(
            ProjectException.class,
            e ->
                assertThat(e.getErrorCode())
                    .isEqualTo(ItemRecommendationErrorCode.TOO_MANY_MATERIALS));
  }

  @Test
  void rejectsSelectionsForUnknownOrDuplicateOrOutOfRangeMaterialIds() {
    activeUser(1L);
    activity(3L);
    stubMaterials(List.of(material(91001L, "계량스푼")));

    // unrelated materialId, not owned by this activity
    assertSelectionRejected(
        new ItemRecommendationRequest.MaterialSelection(99999L, null, 0, null, null));
    // duplicate materialId within the same request
    assertThatThrownBy(
            () ->
                snapshot.read(
                    1L,
                    9001L,
                    new ItemRecommendationRequest(
                        3L,
                        List.of(
                            new ItemRecommendationRequest.MaterialSelection(
                                91001L, null, 0, null, null),
                            new ItemRecommendationRequest.MaterialSelection(
                                91001L, null, 0, null, null)),
                        null)))
        .isInstanceOfSatisfying(
            ProjectException.class,
            e ->
                assertThat(e.getErrorCode())
                    .isEqualTo(ItemRecommendationErrorCode.INVALID_SELECTION));
    // offset out of the 0~1000 bound
    assertSelectionRejected(
        new ItemRecommendationRequest.MaterialSelection(91001L, null, 1001, null, null));
  }

  @Test
  void rejectsANullElementInsideMaterialSelectionsInsteadOfThrowingNpe() {
    activeUser(1L);
    activity(3L);
    stubMaterials(List.of(material(91001L, "계량스푼")));

    // A JSON body like "materialSelections": [null] deserializes to a List with a null entry.
    List<ItemRecommendationRequest.MaterialSelection> withNullEntry =
        java.util.Arrays.asList((ItemRecommendationRequest.MaterialSelection) null);
    assertThatThrownBy(
            () -> snapshot.read(1L, 9001L, new ItemRecommendationRequest(3L, withNullEntry, null)))
        .isInstanceOfSatisfying(
            ProjectException.class,
            e ->
                assertThat(e.getErrorCode())
                    .isEqualTo(ItemRecommendationErrorCode.INVALID_SELECTION));
  }

  @Test
  void rejectsNullElementsInEitherFilterListBeforeCallingAi() {
    activeUser(1L);
    activity(3L);
    stubMaterials(List.of(material(91001L, "계량스푼")));
    var aiClient =
        mock(
            com.hobbytree.hobbytree.domain.itemrecommendation.client.ItemRecommendationAiClient
                .class);
    var service =
        new ItemRecommendationService(
            snapshot, aiClient, mock(com.hobbytree.hobbytree.global.ratelimit.RateLimiter.class));
    for (var filters :
        List.of(
            new ItemRecommendationRequest.Filters(
                java.util.Arrays.asList("M1", null), null, null, List.of()),
            new ItemRecommendationRequest.Filters(
                List.of(), null, null, java.util.Arrays.asList("seen-1", null)))) {
      var request =
          new ItemRecommendationRequest(
              3L,
              List.of(
                  new ItemRecommendationRequest.MaterialSelection(91001L, null, 0, null, filters)),
              null);
      assertThatThrownBy(() -> service.recommend(1L, 9001L, request))
          .isInstanceOfSatisfying(
              ProjectException.class,
              e ->
                  assertThat(e.getErrorCode())
                      .isEqualTo(ItemRecommendationErrorCode.INVALID_SELECTION));
    }
    verifyNoInteractions(aiClient);
  }

  @Test
  void preservesNullOrEmptyFilterListsAndOptionalNullFields() {
    activeUser(1L);
    activity(3L);
    stubMaterials(List.of(material(91001L, "계량스푼")));
    for (var filters :
        java.util.Arrays.asList(
            null,
            new ItemRecommendationRequest.Filters(null, null, null, null),
            new ItemRecommendationRequest.Filters(List.of(), null, null, List.of()))) {
      var result =
          snapshot.read(
              1L,
              9001L,
              new ItemRecommendationRequest(
                  3L,
                  List.of(
                      new ItemRecommendationRequest.MaterialSelection(
                          91001L, null, null, null, filters)),
                  null));
      var selection = result.materialSelections().get(0);
      assertThat(selection.filters().merchantCodes()).isEmpty();
      assertThat(selection.filters().excludeOfferRefs()).isEmpty();
      assertThat(selection.filters().minPriceKrw()).isNull();
      assertThat(selection.filters().maxPriceKrw()).isNull();
      assertThat(selection.budgetKrw()).isNull();
      assertThat(selection.catalogRevision()).isNull();
    }
  }

  @Test
  void rejectsInvertedPriceFilterRangeAndOutOfRangeMaxProductsPerMaterial() {
    activeUser(1L);
    activity(3L);
    stubMaterials(List.of(material(91001L, "계량스푼")));

    assertSelectionRejected(
        new ItemRecommendationRequest.MaterialSelection(
            91001L,
            null,
            0,
            null,
            new ItemRecommendationRequest.Filters(List.of(), 20000L, 1000L, List.of())));
    assertThatThrownBy(() -> snapshot.read(1L, 9001L, new ItemRecommendationRequest(3L, null, 6)))
        .isInstanceOfSatisfying(
            ProjectException.class,
            e ->
                assertThat(e.getErrorCode())
                    .isEqualTo(ItemRecommendationErrorCode.INVALID_SELECTION));
  }

  private void assertSelectionRejected(ItemRecommendationRequest.MaterialSelection selection) {
    assertThatThrownBy(
            () ->
                snapshot.read(
                    1L, 9001L, new ItemRecommendationRequest(3L, List.of(selection), null)))
        .isInstanceOfSatisfying(
            ProjectException.class,
            e ->
                assertThat(e.getErrorCode())
                    .isEqualTo(ItemRecommendationErrorCode.INVALID_SELECTION));
  }

  @Test
  void rejectsNonActiveAccountsBeforeTouchingActivityOrMaterials() {
    var user = mock(User.class);
    when(user.getAccountStatus()).thenReturn(AccountStatus.SUSPENDED);
    when(users.findById(1L)).thenReturn(Optional.of(user));
    assertThatThrownBy(
            () -> snapshot.read(1L, 9001L, new ItemRecommendationRequest(3L, null, null)))
        .isInstanceOfSatisfying(
            AuthException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.SUSPENDED));
    verifyNoInteractions(activities, materials);
  }
}
