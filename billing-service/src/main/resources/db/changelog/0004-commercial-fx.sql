SET LOCAL ROLE taska_billing_migrator;
SET LOCAL search_path=pg_catalog;
CREATE TABLE taska.fx_quotes (
 id uuid PRIMARY KEY, base_currency char(3) NOT NULL CHECK(base_currency='EUR'), quote_currency char(3) NOT NULL CHECK(quote_currency='USD'),
 rate numeric(20,10) NOT NULL CHECK(rate>0), source text NOT NULL,
 quoted_at timestamptz NOT NULL, valid_until timestamptz NOT NULL, version text NOT NULL,
 environment text NOT NULL CHECK(environment IN ('COMMERCIAL_ENVIRONMENT_SANDBOX','COMMERCIAL_ENVIRONMENT_LIVE')),
 configuration_version bigint NOT NULL CHECK(configuration_version>0), CHECK(valid_until>quoted_at)
);
CREATE INDEX fx_quotes_source_idx ON taska.fx_quotes(base_currency,quote_currency,quoted_at DESC);
CREATE FUNCTION taska.protect_fx_quote() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN RAISE EXCEPTION 'Immutable FX quote' USING ERRCODE='23514'; END $$;
CREATE TRIGGER immutable_fx_quote BEFORE UPDATE OR DELETE ON taska.fx_quotes FOR EACH ROW EXECUTE FUNCTION taska.protect_fx_quote();
REVOKE ALL ON taska.fx_quotes FROM PUBLIC,taska_billing_app;
GRANT SELECT,INSERT ON taska.fx_quotes TO taska_billing_app;
