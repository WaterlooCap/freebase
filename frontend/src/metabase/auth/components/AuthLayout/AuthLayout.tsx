import type { ReactNode } from "react";

import { LighthouseIllustration } from "metabase/common/components/LighthouseIllustration";
import { LogoIcon } from "metabase/common/components/LogoIcon";
import { useSelector } from "metabase/redux";
import { getLoginPageIllustration } from "metabase/selectors/whitelabel";
import { Box } from "metabase/ui";

import S from "./AuthLayout.module.css";
import {
  LayoutBody,
  LayoutCard,
  LayoutIllustration,
  LayoutRoot,
} from "./AuthLayout.styled";
interface AuthLayoutProps {
  children?: ReactNode;
}

export const AuthLayout = ({ children }: AuthLayoutProps): JSX.Element => {
  const loginPageIllustration = useSelector(getLoginPageIllustration);

  return (
    <LayoutRoot data-testid="login-page">
      {loginPageIllustration &&
        (loginPageIllustration.isDefault ? (
          <Box className={S.lighthouseFrame}>
            <LighthouseIllustration />
          </Box>
        ) : (
          <LayoutIllustration
            data-testid="login-page-illustration"
            backgroundImageSrc={loginPageIllustration.src}
          />
        ))}
      <LayoutBody>
        <LogoIcon height={48} />
        <LayoutCard>{children}</LayoutCard>
      </LayoutBody>
    </LayoutRoot>
  );
};
