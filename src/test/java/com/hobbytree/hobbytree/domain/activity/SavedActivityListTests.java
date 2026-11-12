package com.hobbytree.hobbytree.domain.activity;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.hobbytree.hobbytree.domain.activity.repository.ActivityRepository;
import com.hobbytree.hobbytree.domain.activity.repository.SavedActivityRepository;
import com.hobbytree.hobbytree.domain.activity.repository.SavedActivityRepository.SavedItem;
import com.hobbytree.hobbytree.domain.activity.service.SavedActivityCursor;
import com.hobbytree.hobbytree.domain.activity.service.SavedActivityService;
import com.hobbytree.hobbytree.domain.experience.service.ExperienceReservationService;
import com.hobbytree.hobbytree.domain.hobby.entity.IndoorOutdoor;
import com.hobbytree.hobbytree.domain.user.controller.UserController;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.domain.user.service.UserService;
import com.hobbytree.hobbytree.global.exception.GeneralExceptionAdvice;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;

class SavedActivityListTests {
  private static final String PATH = "/api/v1/users/me/saved-activities";
  private static final long USER_ID = 17L;
  private static final Instant SAVED_AT = Instant.parse("2026-10-29T06:00:00.123456Z");
  private final UserRepository users = mock(UserRepository.class);
  private final SavedActivityRepository saved = mock(SavedActivityRepository.class);
  private final User user = mock(User.class);
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    var cursor = new SavedActivityCursor(new SecretKeySpec(new byte[32], "HmacSHA256"));
    var service = new SavedActivityService(users, mock(ActivityRepository.class), saved, cursor);
    mvc =
        standaloneSetup(
                new UserController(
                    mock(UserService.class), mock(ExperienceReservationService.class), service))
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .setControllerAdvice(new GeneralExceptionAdvice())
            .build();
    authenticate(USER_ID);
    when(users.findById(USER_ID)).thenReturn(Optional.of(user));
    when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
  }

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void returnsExactPrivateShapeAndContinuesFromLastVisibleRowAtSameTimestamp() throws Exception {
    var available = item(103L, true);
    when(available.getTitle()).thenReturn("명암 넣기");
    when(available.getThumbnailUrl()).thenReturn("https://cdn.example.com/drawing.png");
    when(available.getDurationMinutes()).thenReturn(30);
    when(available.getEstimatedCost()).thenReturn(0);
    when(available.getDifficulty()).thenReturn("BEGINNER");
    when(available.getIndoorOutdoor()).thenReturn(IndoorOutdoor.INDOOR);
    when(available.getHobbyId()).thenReturn(10L);
    when(available.getHobbyName()).thenReturn("드로잉");
    when(available.getStageId()).thenReturn(101L);
    when(available.getStageNumber()).thenReturn((short) 0);
    var hidden = item(102L, false);
    when(hidden.getTitle()).thenReturn("비공개 원문");
    var next = item(101L, false);
    when(saved.findMyPage(USER_ID, true, 10, false, Instant.EPOCH, 0, PageRequest.of(0, 3)))
        .thenReturn(List.of(available, hidden, next));
    when(saved.findMyPage(USER_ID, true, 10, true, SAVED_AT, 102, PageRequest.of(0, 3)))
        .thenReturn(List.of(next));

    var body =
        mvc.perform(get(PATH).param("hobbyId", "10").param("size", "2"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.isSuccess").value(true))
            .andExpect(jsonPath("$.code").value("SAVED200_3"))
            .andExpect(jsonPath("$.message").value("저장한 활동 목록을 조회했습니다."))
            .andExpect(jsonPath("$.result.items.length()").value(2))
            .andExpect(jsonPath("$.result.items[0]", aMapWithSize(14)))
            .andExpect(jsonPath("$.result.items[0].stageNumber").value(1))
            .andExpect(jsonPath("$.result.items[0].isAvailable").value(true))
            .andExpect(jsonPath("$.result.items[0].isSaved").value(true))
            .andExpect(jsonPath("$.result.items[1]", aMapWithSize(4)))
            .andExpect(jsonPath("$.result.items[1].activityId").value(102))
            .andExpect(jsonPath("$.result.items[1].savedAt").exists())
            .andExpect(jsonPath("$.result.items[1].isAvailable").value(false))
            .andExpect(jsonPath("$.result.items[1].title").value("이용할 수 없는 활동입니다"))
            .andExpect(jsonPath("$.result.hasNext").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String cursor = JsonPath.read(body, "$.result.nextCursor");
    mvc.perform(get(PATH).param("hobbyId", "10").param("size", "2").param("cursor", cursor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.items.length()").value(1))
        .andExpect(jsonPath("$.result.items[0].activityId").value(101))
        .andExpect(jsonPath("$.result.nextCursor").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.result.hasNext").value(false));
  }

  @Test
  void defaultPageSizeReturnsEmptyList() throws Exception {
    when(saved.findMyPage(USER_ID, false, 0, false, Instant.EPOCH, 0, PageRequest.of(0, 21)))
        .thenReturn(List.of());
    mvc.perform(get(PATH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.items").isEmpty())
        .andExpect(jsonPath("$.result.nextCursor").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.result.hasNext").value(false));
    org.mockito.Mockito.verify(saved)
        .findMyPage(USER_ID, false, 0, false, Instant.EPOCH, 0, PageRequest.of(0, 21));
  }

  @ParameterizedTest
  @CsvSource({"size,0", "size,51", "size,abc", "hobbyId,-1", "hobbyId,no", "cursor,invalid"})
  void rejectsInvalidQueryBeforeListing(String name, String value) throws Exception {
    mvc.perform(get(PATH).param(name, value))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COMMON400_1"));
    verifyNoInteractions(saved);
  }

  @Test
  void rejectsTamperedAndWrongScopeCursors() throws Exception {
    var cursor = new SavedActivityCursor(new SecretKeySpec(new byte[32], "HmacSHA256"));
    String token = cursor.encode(USER_ID, 10, new SavedActivityCursor.Position(SAVED_AT, 102));
    mvc.perform(get(PATH).param("hobbyId", "11").param("cursor", token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COMMON400_1"));
    String changed = (token.startsWith("A") ? "B" : "A") + token.substring(1);
    mvc.perform(get(PATH).param("hobbyId", "10").param("cursor", changed))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COMMON400_1"));
    authenticate(18L);
    when(users.findById(18L)).thenReturn(Optional.of(user));
    mvc.perform(get(PATH).param("hobbyId", "10").param("cursor", token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COMMON400_1"));
    verifyNoInteractions(saved);
  }

  @ParameterizedTest
  @CsvSource({"PENDING_ONBOARDING,AUTH403_2", "SUSPENDED,AUTH403_1", "WITHDRAWN,AUTH403_3"})
  void rejectsInactiveMembers(AccountStatus accountStatus, String code) throws Exception {
    when(user.getAccountStatus()).thenReturn(accountStatus);
    mvc.perform(get(PATH))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(code));
    verifyNoInteractions(saved);
  }

  @Test
  void rejectsMissingPrincipal() throws Exception {
    clearAuthentication();
    mvc.perform(get(PATH))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH401_2"));
    verifyNoInteractions(saved);
  }

  private static void authenticate(long userId) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(userId, null, List.of()));
  }

  private static SavedItem item(long id, boolean available) {
    var row = mock(SavedItem.class);
    when(row.getActivityId()).thenReturn(id);
    when(row.getAvailable()).thenReturn(available);
    when(row.getSavedAt()).thenReturn(SAVED_AT);
    return row;
  }
}
