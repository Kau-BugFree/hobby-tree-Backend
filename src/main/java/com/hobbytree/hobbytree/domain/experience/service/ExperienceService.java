package com.hobbytree.hobbytree.domain.experience.service;

import com.hobbytree.hobbytree.domain.activity.entity.Activity;
import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.experience.code.ExperienceErrorCode;
import com.hobbytree.hobbytree.domain.experience.dto.ActivityRecognitionResponse;
import com.hobbytree.hobbytree.domain.experience.dto.ExperienceDetailResponse;
import com.hobbytree.hobbytree.domain.experience.dto.ExperienceItemResponse;
import com.hobbytree.hobbytree.domain.experience.dto.ExperienceListResponse;
import com.hobbytree.hobbytree.domain.experience.dto.NextSessionResponse;
import com.hobbytree.hobbytree.domain.experience.dto.ProviderSummaryResponse;
import com.hobbytree.hobbytree.domain.experience.dto.RegionSummaryResponse;
import com.hobbytree.hobbytree.domain.experience.dto.SessionDetailResponse;
import com.hobbytree.hobbytree.domain.experience.entity.Experience;
import com.hobbytree.hobbytree.domain.experience.entity.ExperienceProvider;
import com.hobbytree.hobbytree.domain.experience.entity.ExperienceSession;
import com.hobbytree.hobbytree.domain.experience.entity.ExperienceType;
import com.hobbytree.hobbytree.domain.experience.entity.ListingStatus;
import com.hobbytree.hobbytree.domain.experience.repository.ExperienceProviderRepository;
import com.hobbytree.hobbytree.domain.experience.repository.ExperienceRepository;
import com.hobbytree.hobbytree.domain.experience.repository.ExperienceSessionRepository;
import com.hobbytree.hobbytree.domain.hobby.entity.Hobby;
import com.hobbytree.hobbytree.domain.notification.service.NotificationSubscriptionService;
import com.hobbytree.hobbytree.domain.region.entity.Region;
import com.hobbytree.hobbytree.domain.region.repository.RegionRepository;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class ExperienceService {
  private static final int DEFAULT_SIZE = 20;
  private static final int MAX_SIZE = 50;
  private static final String CURRENCY = "KRW";
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  private static final Set<String> ALLOWED_TYPES = Set.of("CLASS", "MEETUP");
  private static final Set<String> ALLOWED_STATUSES = Set.of("COMING_SOON", "OPEN", "CLOSED");
  // Control character delimiter, same reasoning as HobbyService's cursor: can never appear in
  // a title/experienceId, unlike "_".
  private static final String CURSOR_DELIMITER = "\u0001";

  private final UserRepository users;
  private final ExperienceRepository experiences;
  private final ExperienceSessionRepository sessions;
  private final ExperienceProviderRepository providers;
  private final RegionRepository regions;
  private final NotificationSubscriptionService notificationSubscriptions;
  private final Clock clock;
  private final ObjectMapper objectMapper;

  // open-in-view is disabled in the ec2 profile - without a transaction here, Experience.hobby
  // (JOIN FETCH'd by findCandidates) would be fine, but reading it after findCandidates still
  // benefits from staying inside one transaction for consistency with the rest of the codebase.
  @Transactional(readOnly = true)
  public ExperienceListResponse list(
      Long userId,
      Long hobbyId,
      Long regionId,
      String type,
      String status,
      String fromDate,
      String toDate,
      String sort,
      String cursor,
      Integer size) {
    User user =
        users.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    if (user.getAccountStatus() == AccountStatus.PENDING_ONBOARDING)
      throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);

    ExperienceType parsedType = parseType(type);
    ListingStatus parsedStatus = parseStatus(status);
    boolean sortPrice = parseSort(sort);
    int pageSize = parseSize(size);
    Instant fromInstant = parseDateStart(fromDate);
    Instant toInstant = parseDateEnd(toDate, fromDate);
    boolean hasDateFilter = fromDate != null || toDate != null;
    CursorPosition position = decodeCursor(cursor, sortPrice);

    List<Experience> candidates =
        experiences.findCandidates(
            hobbyId != null,
            hobbyId,
            regionId != null,
            regionId,
            parsedType != null,
            parsedType,
            parsedStatus != null,
            parsedStatus);
    if (candidates.isEmpty()) return new ExperienceListResponse(List.of(), null, false);

    List<Long> experienceIds = candidates.stream().map(Experience::getExperienceId).toList();
    Instant now = clock.instant();
    Map<Long, ExperienceSession> nextSessionByExperienceId = new LinkedHashMap<>();
    for (ExperienceSession s : sessions.findUpcomingByExperienceIds(experienceIds, now)) {
      if (hasDateFilter && !withinRange(s.getStartsAt(), fromInstant, toInstant)) continue;
      // Query orders by startsAt ASC, so the first one seen per experience is the earliest.
      nextSessionByExperienceId.putIfAbsent(s.getExperienceId(), s);
    }

    List<Candidate> rows = new ArrayList<>();
    for (Experience e : candidates) {
      ExperienceSession next = nextSessionByExperienceId.get(e.getExperienceId());
      // A date-range filter narrows to experiences with an upcoming session in that window -
      // one with none there isn't a match, unlike the unfiltered case where "no session yet"
      // is still a valid listing (just sorted/shown with nextSession=null).
      if (hasDateFilter && next == null) continue;
      rows.add(new Candidate(e, next));
    }
    if (rows.isEmpty()) return new ExperienceListResponse(List.of(), null, false);

    Set<Long> regionIds =
        rows.stream()
            .map(c -> c.experience().getRegionId())
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Map<Long, String> regionNameById =
        regions.findAllById(regionIds).stream()
            .collect(Collectors.toMap(Region::getRegionId, Region::getFullName));
    Set<Long> providerIds =
        rows.stream()
            .map(c -> c.experience().getProviderId())
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Map<Long, String> providerNameById =
        providers.findAllById(providerIds).stream()
            .collect(
                Collectors.toMap(ExperienceProvider::getProviderId, ExperienceProvider::getName));

    Comparator<Candidate> comparator =
        sortPrice
            ? Comparator.comparing((Candidate c) -> priceSortValue(c.experience()))
                .thenComparing(c -> c.experience().getExperienceId())
            : Comparator.comparing((Candidate c) -> startsAtSortValue(c.nextSession()))
                .thenComparing(c -> c.experience().getExperienceId());
    rows.sort(comparator);

    List<Candidate> afterCursor =
        position == null
            ? rows
            : rows.stream().filter(c -> isAfterCursor(c, position, sortPrice)).toList();

    boolean hasNext = afterCursor.size() > pageSize;
    List<Candidate> page = hasNext ? afterCursor.subList(0, pageSize) : afterCursor;
    String nextCursor = hasNext ? encodeCursor(page.get(page.size() - 1), sortPrice) : null;

    List<ExperienceItemResponse> items =
        page.stream().map(c -> toItem(c, regionNameById, providerNameById)).toList();
    return new ExperienceListResponse(items, nextCursor, hasNext);
  }

  // API-36: 공개 중인 클래스·모임 하나의 상세. "sessions"는 다가오는 회차만 보여준다(지나간
  // 회차는 더 이상 예약 가능 대상이 아니므로 제외) - API.md에 범위가 명시돼 있지 않아 내린
  // 판단.
  @Transactional(readOnly = true)
  public ExperienceDetailResponse detail(Long userId, Long experienceId) {
    User user =
        users.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    if (user.getAccountStatus() == AccountStatus.PENDING_ONBOARDING)
      throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);

    Experience e =
        experiences
            .findDetailCandidateById(experienceId)
            .orElseThrow(() -> new ProjectException(ExperienceErrorCode.DETAIL_NOT_FOUND));

    List<ExperienceSession> upcoming =
        sessions.findUpcomingByExperienceIds(List.of(experienceId), clock.instant());
    ExperienceSession next = upcoming.isEmpty() ? null : upcoming.get(0);

    RegionSummaryResponse region =
        e.getRegionId() == null
            ? null
            : regions
                .findById(e.getRegionId())
                .map(r -> new RegionSummaryResponse(r.getRegionId(), r.getFullName()))
                .orElse(null);
    ProviderSummaryResponse provider =
        e.getProviderId() == null
            ? null
            : providers
                .findById(e.getProviderId())
                .map(p -> new ProviderSummaryResponse(p.getProviderId(), p.getName()))
                .orElse(null);

    Hobby hobby = e.getHobby();
    Activity linkedActivity = e.getLinkedActivity();
    // "활동 인정 연동이 없는 상품은 supported:false와 activityId:null을 반환한다" (API.md 비고).
    ActivityRecognitionResponse recognition =
        new ActivityRecognitionResponse(
            linkedActivity != null,
            linkedActivity == null ? null : linkedActivity.getActivityId(),
            linkedActivity != null);
    boolean subscribed = notificationSubscriptions.isSubscribedToClassOpen(userId, experienceId);

    return new ExperienceDetailResponse(
        e.getExperienceId(),
        e.getExperienceType().name(),
        e.getTitle(),
        hobby == null ? null : hobby.getHobbyId(),
        hobby == null ? null : hobby.getHobbyName(),
        hobby == null ? null : hobby.getThumbnailUrl(),
        region,
        provider,
        e.getPriceAmount(),
        CURRENCY,
        e.getListingStatus().name(),
        next == null
            ? null
            : new NextSessionResponse(
                next.getSessionId(), next.getStartsAt(), next.getDurationMinutes()),
        e.getUpdatedAt(),
        e.getDescription(),
        e.getVenueAddress(),
        e.getVenueName(),
        parseStringList(e.getMaterials()),
        upcoming.stream().map(s -> toSessionDetail(s, e.getListingStatus())).toList(),
        e.getExternalUrl(),
        e.getCancellationPolicyUrl(),
        recognition,
        subscribed);
  }

  private SessionDetailResponse toSessionDetail(ExperienceSession s, ListingStatus listingStatus) {
    Instant endsAt = s.getStartsAt().plus(s.getDurationMinutes(), ChronoUnit.MINUTES);
    // remainingSeats는 세션 자체의 capacity 그대로다 - API-37이 예약을 세션에 연결하기 전까지는
    // 뺄 예약 건수가 없다("잔여 좌석은 마지막 연동 시점 정보이며 예약 확정을 보장하지 않는다",
    // API.md 비고와도 부합).
    Integer remainingSeats = s.getCapacity();
    // listingStatus가 OPEN이 아니면(COMING_SOON이든 CLOSED든) 예약 자체가 불가능하므로 세션을
    // OPEN으로 표시할 수 없다 - API.md에 CLOSED/COMING_SOON 세션 상태가 따로 정의돼 있지 않아,
    // "예약 가능한가"를 기준으로 OPEN/CLOSED 둘로만 나눈다. capacity는 CHECK(capacity > 0)이라
    // remainingSeats만으로는 CLOSED에 절대 도달하지 못하므로 listingStatus를 반드시 함께 봐야 한다.
    String status =
        listingStatus == ListingStatus.OPEN && (remainingSeats == null || remainingSeats > 0)
            ? "OPEN"
            : "CLOSED";
    return new SessionDetailResponse(
        s.getSessionId(), s.getStartsAt(), endsAt, status, remainingSeats);
  }

  private List<String> parseStringList(String json) {
    List<String> result = new ArrayList<>();
    objectMapper.readTree(json).forEach(n -> result.add(n.asText()));
    return result;
  }

  private ExperienceItemResponse toItem(
      Candidate c, Map<Long, String> regionNameById, Map<Long, String> providerNameById) {
    Experience e = c.experience();
    Hobby hobby = e.getHobby();
    RegionSummaryResponse region =
        e.getRegionId() == null
            ? null
            : new RegionSummaryResponse(e.getRegionId(), regionNameById.get(e.getRegionId()));
    ProviderSummaryResponse provider =
        e.getProviderId() == null
            ? null
            : new ProviderSummaryResponse(
                e.getProviderId(), providerNameById.get(e.getProviderId()));
    NextSessionResponse nextSession =
        c.nextSession() == null
            ? null
            : new NextSessionResponse(
                c.nextSession().getSessionId(),
                c.nextSession().getStartsAt(),
                c.nextSession().getDurationMinutes());
    return new ExperienceItemResponse(
        e.getExperienceId(),
        e.getExperienceType().name(),
        e.getTitle(),
        hobby == null ? null : hobby.getHobbyId(),
        hobby == null ? null : hobby.getHobbyName(),
        hobby == null ? null : hobby.getThumbnailUrl(),
        region,
        provider,
        e.getPriceAmount(),
        CURRENCY,
        e.getListingStatus().name(),
        nextSession,
        e.getUpdatedAt());
  }

  private boolean withinRange(Instant value, Instant from, Instant to) {
    return (from == null || !value.isBefore(from)) && (to == null || value.isBefore(to));
  }

  private long priceSortValue(Experience e) {
    return e.getPriceAmount() == null ? Long.MAX_VALUE : e.getPriceAmount();
  }

  private long startsAtSortValue(ExperienceSession next) {
    return next == null ? Long.MAX_VALUE : next.getStartsAt().toEpochMilli();
  }

  private ExperienceType parseType(String type) {
    if (type == null || type.isBlank()) return null;
    if (!ALLOWED_TYPES.contains(type))
      throw new ProjectException(ExperienceErrorCode.INVALID_FILTER);
    return ExperienceType.valueOf(type);
  }

  private ListingStatus parseStatus(String status) {
    if (status == null || status.isBlank()) return null;
    if (!ALLOWED_STATUSES.contains(status))
      throw new ProjectException(ExperienceErrorCode.INVALID_FILTER);
    return ListingStatus.valueOf(status);
  }

  private boolean parseSort(String sort) {
    if (sort == null || sort.isBlank() || "STARTS_AT".equals(sort)) return false;
    if ("PRICE_ASC".equals(sort)) return true;
    throw new ProjectException(ExperienceErrorCode.INVALID_FILTER);
  }

  private int parseSize(Integer size) {
    if (size == null) return DEFAULT_SIZE;
    if (size < 1 || size > MAX_SIZE) throw new ProjectException(ExperienceErrorCode.INVALID_FILTER);
    return size;
  }

  private Instant parseDateStart(String date) {
    if (date == null) return null;
    try {
      return LocalDate.parse(date).atStartOfDay(SEOUL).toInstant();
    } catch (DateTimeParseException e) {
      throw new ProjectException(ExperienceErrorCode.INVALID_FILTER);
    }
  }

  // Exclusive upper bound: the instant at which the day AFTER toDate begins.
  private Instant parseDateEnd(String toDate, String fromDate) {
    if (toDate == null) return null;
    LocalDate parsedToDate;
    try {
      parsedToDate = LocalDate.parse(toDate);
    } catch (DateTimeParseException e) {
      throw new ProjectException(ExperienceErrorCode.INVALID_FILTER);
    }
    if (fromDate != null) {
      try {
        if (parsedToDate.isBefore(LocalDate.parse(fromDate)))
          throw new ProjectException(ExperienceErrorCode.INVALID_FILTER);
      } catch (DateTimeParseException e) {
        throw new ProjectException(ExperienceErrorCode.INVALID_FILTER);
      }
    }
    return parsedToDate.plusDays(1).atStartOfDay(SEOUL).toInstant();
  }

  private boolean isAfterCursor(Candidate c, CursorPosition position, boolean sortPrice) {
    long value = sortPrice ? priceSortValue(c.experience()) : startsAtSortValue(c.nextSession());
    if (value != position.sortValue()) return value > position.sortValue();
    return c.experience().getExperienceId() > position.experienceId();
  }

  // Opaque cursor: base64("{PRICE|STARTS_AT}\u0001{sortValue}\u0001{experienceId}"). The sort
  // mode travels inside the cursor so a client that changes `sort` mid-pagination gets a clean
  // EXPERIENCE400_1 instead of a silently wrong page, instead of trusting the request's own
  // `sort` - same convention as HobbyService's cursor.
  private CursorPosition decodeCursor(String cursor, boolean sortPrice) {
    if (cursor == null) return null;
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = decoded.split(CURSOR_DELIMITER, 3);
      boolean cursorIsPrice = "PRICE".equals(parts[0]);
      if (cursorIsPrice != sortPrice)
        throw new ProjectException(ExperienceErrorCode.INVALID_FILTER);
      return new CursorPosition(Long.parseLong(parts[1]), Long.parseLong(parts[2]));
    } catch (ProjectException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new ProjectException(ExperienceErrorCode.INVALID_FILTER);
    }
  }

  private String encodeCursor(Candidate last, boolean sortPrice) {
    long value =
        sortPrice ? priceSortValue(last.experience()) : startsAtSortValue(last.nextSession());
    String raw =
        (sortPrice ? "PRICE" : "STARTS_AT")
            + CURSOR_DELIMITER
            + value
            + CURSOR_DELIMITER
            + last.experience().getExperienceId();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private record Candidate(Experience experience, ExperienceSession nextSession) {}

  private record CursorPosition(long sortValue, long experienceId) {}
}
