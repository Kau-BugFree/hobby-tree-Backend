# 온보딩 약관 본문 제공

API-10 `GET /api/v1/onboarding/options`의 `result.terms[]` 및
`result.locationConsentNotice`에서 `contentUrl` 대신 `content`를 반환한다.
프론트는 외부 웹페이지 이동 없이 본문을 앱 내부 화면이나 모달에 표시한다.
`content`는 HTML이 아닌 일반 텍스트이며 줄바꿈은 JSON의 `\n`으로 전달한다.

```json
{
  "terms": [
    {
      "termsId": 1,
      "title": "서비스 이용약관",
      "version": "1.0",
      "isRequired": true,
      "content": "[개발용 임시 본문]\n서비스 이용약관 본문은 아직 확정되지 않았습니다. 실제 서비스 제공 전 확정된 약관으로 교체해야 합니다."
    }
  ],
  "locationConsentNotice": {
    "consentType": "NEARBY_EXPERIENCES",
    "version": "1.0",
    "content": "[개발용 임시 본문]\n위치 정보 동의 안내 본문은 아직 확정되지 않았습니다. 실제 서비스 제공 전 확정된 내용으로 교체해야 합니다."
  }
}
```

위 예시는 응답 일부이다. 실제 terms에는 개인정보 관련 약관 항목도 포함된다.
contentUrl은 제거되므로 프론트는 content를 사용하도록 변경해야 한다.

약관 ID·버전·필수 여부 및 API-13의 agreements 요청 형식은 유지한다.
API-13은 필수 동의와 버전을 검증하고 user_terms_agreements에 이력을 저장한다.
위치 동의 버전도 기존 LocationConsentNotice.CURRENT_VERSION과 일치하도록 유지한다.
DB 스키마·마이그레이션 변경은 없다.

실제 약관 본문이 제공되지 않았으므로 현재 본문은 명시적인 개발용 안내이다.
운영에 사용할 약관 본문·최종 버전·필수 여부를 확정해 교체해야 한다.
실제 약관 개정 시 버전을 갱신하고 기존 동의 이력과 새 동의를 구분해야 한다.
이번 전달 형식 변경은 선택지 값이나 동의 조건을 바꾸지 않으므로 optionsVersion은 유지한다.

OnboardingIntegrationTests에서 본문 필드·줄바꿈·기존 URL 필드 제거와
기존 온보딩 완료·필수 동의·버전 검증 및 동의 이력 저장을 검증한다.
