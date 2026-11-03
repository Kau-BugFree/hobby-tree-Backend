#!/bin/sh
set -eu

domain_reports="build/test-results/test/TEST-com.hobbytree.hobbytree.domain.auth.AuthIntegrationTests.xml build/test-results/test/TEST-com.hobbytree.hobbytree.domain.user.UserIntegrationTests.xml build/test-results/test/TEST-com.hobbytree.hobbytree.domain.notification.NotificationIntegrationTests.xml build/test-results/test/TEST-com.hobbytree.hobbytree.domain.activity.ActivityIntegrationTests.xml"
proxy_report=build/test-results/test/TEST-com.hobbytree.hobbytree.domain.auth.ProxyRateLimitIntegrationTests.xml

rm -f $domain_reports "$proxy_report"
# *IntegrationTests matches every domain's integration suite above, not only auth's, so adding
# a new domain class needs no change here.
./gradlew --offline --no-daemon --max-workers=2 test --tests '*IntegrationTests'

count() {
  sed -n "s/.* $2=\"\([0-9][0-9]*\)\".*/\1/p" "$1"
}

total_tests=0
total_failures=0
total_errors=0
total_skipped=0
for report in $domain_reports; do
  test -f "$report"
  total_tests=$((total_tests + $(count "$report" tests)))
  total_failures=$((total_failures + $(count "$report" failures)))
  total_errors=$((total_errors + $(count "$report" errors)))
  total_skipped=$((total_skipped + $(count "$report" skipped)))
done
# No exact total is hardcoded here (each domain class grows independently); only a clean pass
# across all of them is required.
test "$total_failures" = 0
test "$total_errors" = 0
test "$total_skipped" = 0
printf 'AUTH_INTEGRATION_RESULT tests=%s failures=%s errors=%s skipped=%s\n' \
  "$total_tests" "$total_failures" "$total_errors" "$total_skipped"

test -f "$proxy_report"
test "$(count "$proxy_report" tests)" = 8
test "$(count "$proxy_report" failures)" = 0
test "$(count "$proxy_report" errors)" = 0
test "$(count "$proxy_report" skipped)" = 0
printf 'PROXY_INTEGRATION_RESULT tests=8 failures=0 errors=0 skipped=0\n'
