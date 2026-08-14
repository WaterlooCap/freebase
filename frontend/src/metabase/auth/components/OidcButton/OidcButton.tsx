import { t } from "ttag";

import { Button } from "metabase/common/components/Button";

interface OidcButtonProps {
  redirectUrl?: string;
}

export const OidcButton = ({ redirectUrl }: OidcButtonProps): JSX.Element => {
  const href = redirectUrl
    ? `/auth/sso/oidc?redirect=${encodeURIComponent(redirectUrl)}`
    : "/auth/sso/oidc";

  return (
    <Button as="a" href={href} fullWidth>
      {t`Sign in with SSO`}
    </Button>
  );
};
