import { t } from "ttag";

import { useSelector } from "metabase/redux";
import { getApplicationName } from "metabase/selectors/whitelabel";
import { Anchor, Button } from "metabase/ui";

import S from "../AuthButton/AuthButton.module.css";

interface OidcButtonProps {
  isCard?: boolean;
  redirectUrl?: string;
}

/**
 * Starts the OIDC login flow. `/auth/sso/oidc` is a server route that redirects to the
 * IdP, so this has to be a real anchor — metabase/router's Link would try to resolve it
 * client-side and never reach the backend. The card form is the login page's primary
 * action; the text form sits in the password panel's list of other ways to sign in.
 */
export const OidcButton = ({
  isCard,
  redirectUrl,
}: OidcButtonProps): JSX.Element => {
  const applicationName = useSelector(getApplicationName);
  const href = redirectUrl
    ? `/auth/sso/oidc?redirect=${encodeURIComponent(redirectUrl)}`
    : "/auth/sso/oidc";
  const label = t`Sign in with ${applicationName} SSO`;

  if (isCard) {
    return (
      <Button
        component="a"
        href={href}
        variant="filled"
        fullWidth
        h="3rem"
        fz="md"
      >
        {label}
      </Button>
    );
  }

  return (
    <Anchor href={href} className={S.link} underline="never">
      {label}
    </Anchor>
  );
};
