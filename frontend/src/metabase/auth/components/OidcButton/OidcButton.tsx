import cx from "classnames";
import { t } from "ttag";

import { Anchor, Card } from "metabase/ui";

import S from "../AuthButton/AuthButton.module.css";

interface OidcButtonProps {
  isCard?: boolean;
  redirectUrl?: string;
}

/**
 * Starts the OIDC login flow. `/auth/sso/oidc` is a server route that redirects to the
 * IdP, so this has to be a real anchor — metabase/router's Link would try to resolve it
 * client-side and never reach the backend. Styling mirrors AuthCardLink/AuthTextLink so
 * it sits flush with the other providers on the login page.
 */
export const OidcButton = ({
  isCard,
  redirectUrl,
}: OidcButtonProps): JSX.Element => {
  const href = redirectUrl
    ? `/auth/sso/oidc?redirect=${encodeURIComponent(redirectUrl)}`
    : "/auth/sso/oidc";

  if (isCard) {
    return (
      <Card component="a" href={href} className={cx(S.link, S.card)}>
        {t`Sign in with SSO`}
      </Card>
    );
  }

  return (
    <Anchor href={href} className={S.link} underline="never">
      {t`Sign in with SSO`}
    </Anchor>
  );
};
