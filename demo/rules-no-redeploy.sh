#!/usr/bin/env bash
# demo/rules-no-redeploy.sh
#
# Proves the "no redeploy for new rules" story end-to-end.
# Prerequisites: rules-service running on localhost:8083
#   cd rules-service && mvn spring-boot:run
#
# Usage: bash demo/rules-no-redeploy.sh

set -euo pipefail
BASE="http://localhost:8083"
AUTH="-u analyst:analyst"

echo ""
echo "════════════════════════════════════════════════════════"
echo " Step 1 — evaluate a transaction with NO rules loaded"
echo "════════════════════════════════════════════════════════"
curl -s $AUTH -X POST "$BASE/rules/evaluate" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "demo-user", "amount": 8000,
    "deviceId": "d-clean", "ipAddress": "10.0.0.1",
    "cardCountry": "US", "velocityCount": 1
  }' | jq .
# Expected: decision=ALLOW, triggeredRules=[]

echo ""
echo "════════════════════════════════════════════════════════"
echo " Step 2 — add HIGH_AMOUNT rule via POST /rules"
echo "          (no service restart)"
echo "════════════════════════════════════════════════════════"
RULE=$(curl -s $AUTH -X POST "$BASE/rules" \
  -H "Content-Type: application/json" \
  -d '{"name":"HIGH_AMOUNT","conditionExpression":"amount > 5000","weight":35}')
echo "$RULE" | jq .
RULE_ID=$(echo "$RULE" | jq -r '.id')

echo ""
echo "════════════════════════════════════════════════════════"
echo " Step 3 — evaluate the SAME transaction immediately"
echo "          Rule must fire — zero restart"
echo "════════════════════════════════════════════════════════"
curl -s $AUTH -X POST "$BASE/rules/evaluate" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "demo-user", "amount": 8000,
    "deviceId": "d-clean", "ipAddress": "10.0.0.1",
    "cardCountry": "US", "velocityCount": 1
  }' | jq .
# Expected: decision=REVIEW, triggeredRules=[HIGH_AMOUNT]

echo ""
echo "════════════════════════════════════════════════════════"
echo " Step 4 — add TOR_EXIT_IP rule"
echo "════════════════════════════════════════════════════════"
curl -s $AUTH -X POST "$BASE/rules" \
  -H "Content-Type: application/json" \
  -d '{"name":"TOR_EXIT_IP","conditionExpression":"ipRiskFlags.contains(\"TOR_EXIT\")","weight":40}' | jq .

echo ""
echo "════════════════════════════════════════════════════════"
echo " Step 5 — both rules fire → BLOCK"
echo "════════════════════════════════════════════════════════"
curl -s $AUTH -X POST "$BASE/rules/evaluate" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "demo-user", "amount": 8000,
    "deviceId": "d-clean", "ipAddress": "185.220.0.1",
    "cardCountry": "US", "velocityCount": 1,
    "ipRiskFlags": ["TOR_EXIT"]
  }' | jq .
# Expected: decision=BLOCK, triggeredRules=[HIGH_AMOUNT, TOR_EXIT_IP]

echo ""
echo "════════════════════════════════════════════════════════"
echo " Step 6 — deactivate HIGH_AMOUNT (no restart)"
echo "════════════════════════════════════════════════════════"
curl -s $AUTH -X PATCH "$BASE/rules/$RULE_ID" \
  -H "Content-Type: application/json" \
  -d '{"active": false}' | jq .

echo ""
echo "════════════════════════════════════════════════════════"
echo " Step 7 — only TOR_EXIT_IP fires now"
echo "════════════════════════════════════════════════════════"
curl -s $AUTH -X POST "$BASE/rules/evaluate" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "demo-user", "amount": 8000,
    "deviceId": "d-clean", "ipAddress": "185.220.0.1",
    "cardCountry": "US", "velocityCount": 1,
    "ipRiskFlags": ["TOR_EXIT"]
  }' | jq .
# Expected: triggeredRules=[TOR_EXIT_IP] only

echo ""
echo "════════════════════════════════════════════════════════"
echo " Step 8 — audit log shows CREATE then DEACTIVATE"
echo "════════════════════════════════════════════════════════"
curl -s $AUTH "$BASE/rules/$RULE_ID/audit" | jq .

echo ""
echo "✅  Demo complete — no service was restarted."
