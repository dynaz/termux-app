# Fixtures — synthetic WHMCS `GetClientsProducts` responses

Each file is the `products.product` array the outbox's injected WHMCS `localAPI` returns for one
client. All data is synthetic (service id 5001, product id 101 = the mapped premium). Dates are fixed
(far future / far past) so lifecycle outcomes are deterministic regardless of the run date. No real
customer, service, or account data appears here.
