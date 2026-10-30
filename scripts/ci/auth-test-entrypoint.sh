#!/bin/sh
set -eu
report=build/test-results/test/TEST-com.hobbytree.hobbytree.domain.auth.AuthIntegrationTests.xml
rm -f "$report"
./gradlew --offline --no-daemon --max-workers=2 test --tests '*AuthIntegrationTests' --tests '*ProxyRateLimitIntegrationTests'
test -f "$report"
count() {
  sed -n "s/.* $1=\"\([0-9][0-9]*\)\".*/\1/p" "$report"
}
tests=$(count tests)
failures=$(count failures)
errors=$(count errors)
skipped=$(count skipped)
test "$tests" = 18
test "$failures" = 0
test "$errors" = 0
test "$skipped" = 0
printf 'AUTH_INTEGRATION_RESULT tests=%s failures=%s errors=%s skipped=%s\n' "$tests" "$failures" "$errors" "$skipped"

report=build/test-results/test/TEST-com.hobbytree.hobbytree.domain.auth.ProxyRateLimitIntegrationTests.xml
test -f "$report"
test "$(count tests)" = 8
test "$(count failures)" = 0
test "$(count errors)" = 0
test "$(count skipped)" = 0
printf 'PROXY_INTEGRATION_RESULT tests=8 failures=0 errors=0 skipped=0\n'
