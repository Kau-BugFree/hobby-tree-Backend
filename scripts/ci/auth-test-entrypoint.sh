#!/bin/sh
set -eu

# These specific domains are mandatory: if one's class gets renamed/misspelled (e.g.
# AuthIntegrationTests -> AuthIntegrationTest) and silently drops out of the *IntegrationTests
# glob below, this check catches it by filename even though other domains' reports still exist
# and pass. Additional domain classes are still discovered and aggregated automatically.
required_reports="build/test-results/test/TEST-com.hobbytree.hobbytree.domain.auth.AuthIntegrationTests.xml build/test-results/test/TEST-com.hobbytree.hobbytree.domain.user.UserIntegrationTests.xml build/test-results/test/TEST-com.hobbytree.hobbytree.domain.notification.NotificationIntegrationTests.xml build/test-results/test/TEST-com.hobbytree.hobbytree.domain.activity.ActivityIntegrationTests.xml"
proxy_report=build/test-results/test/TEST-com.hobbytree.hobbytree.domain.auth.ProxyRateLimitIntegrationTests.xml

rm -f build/test-results/test/TEST-*IntegrationTests.xml
# *IntegrationTests matches every domain's integration suite, not only auth's. Reports beyond
# the required ones above are discovered after the run (not listed here), so adding a new
# domain class needs no change to this script.
./gradlew --offline --no-daemon --max-workers=2 test --tests '*IntegrationTests'

count() {
  sed -n "s/.* $2=\"\([0-9][0-9]*\)\".*/\1/p" "$1"
}

for report in $required_reports; do
  test -f "$report"
done

total_tests=0
total_failures=0
total_errors=0
total_skipped=0
domain_reports_found=0
for report in build/test-results/test/TEST-*IntegrationTests.xml; do
  test -f "$report"
  [ "$report" = "$proxy_report" ] && continue
  domain_reports_found=$((domain_reports_found + 1))
  total_tests=$((total_tests + $(count "$report" tests)))
  total_failures=$((total_failures + $(count "$report" failures)))
  total_errors=$((total_errors + $(count "$report" errors)))
  total_skipped=$((total_skipped + $(count "$report" skipped)))
done
# No exact total or report count is hardcoded here (each domain class grows independently);
# the required-report check above is what guards against a silently missing domain.
test "$domain_reports_found" -gt 0
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
