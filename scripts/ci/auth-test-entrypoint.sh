#!/bin/sh
set -eu

proxy_report=build/test-results/test/TEST-com.hobbytree.hobbytree.domain.auth.ProxyRateLimitIntegrationTests.xml

rm -f build/test-results/test/TEST-*IntegrationTests.xml
# *IntegrationTests matches every domain's integration suite, not only auth's. Reports are
# discovered after the run below (not listed here), so adding a new domain class needs no
# change to this script.
./gradlew --offline --no-daemon --max-workers=2 test --tests '*IntegrationTests'

count() {
  sed -n "s/.* $2=\"\([0-9][0-9]*\)\".*/\1/p" "$1"
}

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
# only a clean pass across whichever domain reports actually exist is required.
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
