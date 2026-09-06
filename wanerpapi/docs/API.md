# wanerpapi API contract notes

## External architecture

```text
Oracle APEX / other NEA client
          |
          v
api.nea.wantoksys.com       (future Spring Boot facade)
          |
          v
erp.nea.wantoksys.com/rest  (OFBiz rest-api)
          |
          v
plugins/wanerpapi            (this controlled adapter)
          |
          +--> OFBiz Service Engine
          +--> OFBiz Entity Engine
```

The Spring Boot facade should translate these service results into stable DTOs and should own KumulPay callback validation, public authentication, rate limiting, correlation IDs and external idempotency policy.

## Native services deliberately called internally

The plugin wraps, rather than exports directly, the relevant native OFBiz operations, including:

- `calculateProductPrice`
- `getInventoryAvailableByFacility`
- `createInvoiceForOrderAllItems`
- `createPayment`
- `setPaymentStatus`
- `createPaymentApplication`
- `checkPaymentInvoices`
- Product/catalog/category/party setup services

Sales-order creation is performed through `ShoppingCart` + `CheckOutHelper.createOrder()`, which uses OFBiz order services internally.

## ProductStore traversal

`wanerpApiGetProductsByProductStore` traverses:

```text
ProductStore
  -> ProductStoreCatalog
  -> ProdCatalog
  -> ProdCatalogCategory
  -> ProductCategory
  -> ProductCategoryRollup (recursive descendants)
  -> ProductCategoryMember
  -> Product
  -> GoodIdentification
```

Only date-active relationships are returned by default.

## Payment lifecycle

`wanerpApiMarkOrderPaid` is intentionally named for the facade-facing concept but it does not write an artificial order status. It reconciles the payment and invoice and returns whether the invoice reached `INVOICE_PAID`.

## Admin API boundary

The `wanerpAdmin*` services are intended for controlled demo/setup administration. They are not the long-term public tenant provisioning API.


## NEA end-user customer registration

`wanerpApiRegisterEndUserCustomer` is the preferred runtime contract for `enduser.nea.wantoksys.com`. The browser should not call OFBiz directly. The Spring Boot facade authenticates to OFBiz and calls this service after the application identity has been created/verified.

The service creates or reconciles:

- `Party` / `Person`
- `PartyRole(roleTypeId=CUSTOMER)`
- primary email contact mechanism
- optional telecom number
- optional postal address

Use the identity-provider subject (for example the Keycloak `sub`) as `externalId`. A retry with the same `externalId` returns the same OFBiz `partyIdOut` instead of creating another customer. If `externalId` is not available, `idempotencyKey` is accepted as the fallback identity key. Never send the end-user password to this API.
