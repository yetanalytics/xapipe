[<- Back to Index](index.md)
# OAuth Support

LRSPipe supports the use of [OAuth 2.0](https://oauth.net/2/) with LRS endpoints that support it via the [Client Credentials Grant](https://tools.ietf.org/html/rfc6749#section-4.4)

## Client Credentials Grant

To use OAuth, specify a source/target `auth-uri`, `client-id` and `client-secret`:

``` shell
bin/run.sh --source-url http://0.0.0.0:8080/xapi \
           --source-auth-uri http://0.0.0.0:8083/auth/realms/test/protocol/openid-connect \
           --source-client-id a_client_id \
           --source-client-secret 1234 \
           --target-url http://0.0.0.0:8081/xapi \
           --target-auth-uri http://0.0.0.0:8083/auth/realms/test/protocol/openid-connect \
           --target-client-id b_client_id \
           --target-client-secret 1234
```

LRSPipe will connect to the specified auth provider(s) and provide up-to-date tokens for LRS requests as needed.

### Scope

According to OAuth 2.0 an optional `scope` parameter can be provided on Client Credentials Grant requests. To set this value for the source/target LRS:

``` shell
bin/run.sh ... \
           --source-scope "lrs:read" \
           --target-scope "lrs:write"
```

Note that the configuration of claims like scope should be done on the OAuth client itself. This option is provided for backwards compatibility only.

## Manual Bearer Token Usage

If you have a bearer token that will be valid for the duration of your job, you can pass it directly:

``` shell
bin/run.sh --source-url http://0.0.0.0:8080/xapi \
           --source-token eyJhbGciOi...
           --target-url http://0.0.0.0:8081/xapi \
           --target-token eyJhbGciOi...
```

[<- Back to Index](index.md)
