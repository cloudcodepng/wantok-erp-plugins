# NEA end-user registration -> OFBiz customer

## Architecture

```text
Browser: enduser.nea.wantoksys.com
       |
       | registration / identity verification
       v
Future Spring Boot facade: api.nea.wantoksys.com
       |
       | trusted OFBiz integration credential
       v
wanerpApiRegisterEndUserCustomer
       |
       +--> Party (PERSON)
       +--> Person
       +--> PartyRole = CUSTOMER
       +--> PRIMARY_EMAIL
       +--> optional TelecomNumber
       +--> optional PostalAddress
```

The browser must not receive the OFBiz integration credential and should not call `/rest/services/...` directly.

## Identity / idempotency rule

Pass the identity-provider user ID (recommended: Keycloak `sub`) as `externalId`.

Example:

```json
{
  "externalId": "03db1889-74c1-49f8-b0aa-5b5b267463be",
  "idempotencyKey": "enduser-register-03db1889-74c1-49f8-b0aa-5b5b267463be"
}
```

The OFBiz `Party.externalId` becomes the stable link to the application identity. The API also checks the normalized email address. If identity and email resolve to two different OFBiz parties, it returns an error instead of merging them silently.

## Minimum registration request

```bash
curl -sS -X POST -G \
  "$BASE/rest/services/wanerpApiRegisterEndUserCustomer" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={
    "firstName":"Meri",
    "lastName":"Demo",
    "emailAddress":"meri.demo@example.com",
    "preferredCurrencyUomId":"PGK",
    "externalId":"keycloak-sub-nea-demo-001",
    "idempotencyKey":"enduser-register-keycloak-sub-nea-demo-001"
  }' | jq
```

Expected shape:

```json
{
  "partyIdOut": "...",
  "externalIdOut": "keycloak-sub-nea-demo-001",
  "emailAddressOut": "meri.demo@example.com",
  "emailContactMechId": "...",
  "customerRoleEnsured": true,
  "created": true,
  "duplicate": false
}
```

Repeating the request returns the same `partyIdOut`, with `created=false` and `duplicate=true`.

## Full contact example

```bash
curl -sS -X POST -G \
  "$BASE/rest/services/wanerpApiRegisterEndUserCustomer" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={
    "firstName":"Meri",
    "middleName":"NEA",
    "lastName":"Demo",
    "emailAddress":"meri.demo@example.com",
    "countryCode":"675",
    "contactNumber":"71234567",
    "address1":"Section 1 Lot 1",
    "city":"Port Moresby",
    "postalCode":"111",
    "countryGeoId":"PNG",
    "preferredCurrencyUomId":"PGK",
    "externalId":"keycloak-sub-nea-demo-001",
    "idempotencyKey":"enduser-register-keycloak-sub-nea-demo-001"
  }' | jq
```

Postal address creation is only attempted when address fields are supplied. OFBiz requires `address1`, `city`, and `postalCode` together.

## Verify the customer

```bash
PARTY_ID='REPLACE_WITH_partyIdOut'
curl -sS -G \
  "$BASE/rest/services/wanerpApiGetParty" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode "inParams={\"partyId\":\"$PARTY_ID\"}" | jq
```

The response includes `roles`, `emails`, `telecomNumbers`, and `postalAddresses`.

## Admin/manual test alias

For controlled manual testing only:

`wanerpAdminRegisterEndUserCustomer`

It invokes exactly the same Java implementation. Do not wire `enduser.nea.wantoksys.com` to the `wanerpAdmin*` endpoint.

## Spring Boot facade mapping

Recommended facade endpoint:

`POST /api/v1/customers/register`

Suggested flow:

1. Validate the caller/registration in the end-user identity layer.
2. Obtain the stable identity subject (`sub`).
3. Build the OFBiz request using `sub -> externalId`.
4. Authenticate the facade to OFBiz with the dedicated integration account.
5. Call `wanerpApiRegisterEndUserCustomer`.
6. Return/store `partyIdOut` as the ERP customer identifier.
7. Never send or persist the user's password in the OFBiz registration API.

## Runtime verification script

After the plugin compiles and OFBiz is running locally:

```bash
BASE=http://localhost:8080 \
OFBIZ_USER=admin \
OFBIZ_PASS=ofbiz \
plugins/wanerpapi/scripts/test-enduser-registration.sh
```

The script registers the same identity twice, confirms the same `partyIdOut` is returned, and reads the Party back through `wanerpApiGetParty`.
