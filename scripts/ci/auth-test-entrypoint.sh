#!/bin/sh
set -eu
report=build/test-results/test/TEST-com.hobbytree.hobbytree.domain.auth.AuthIntegrationTests.xml
rm -f "$report"
./gradlew --offline --no-daemon --max-workers=2 test --tests '*AuthIntegrationTests'
test -f "$report"
count() {
  sed -n "s/.* $1=\"\([0-9][0-9]*\)\".*/\1/p" "$report"
}
tests=$(count tests)
failures=$(count failures)
errors=$(count errors)
skipped=$(count skipped)
test "$tests" = 30
test "$failures" = 0
test "$errors" = 0
test "$skipped" = 0
printf 'AUTH_INTEGRATION_RESULT tests=%s failures=%s errors=%s skipped=%s\n' "$tests" "$failures" "$errors" "$skipped"
