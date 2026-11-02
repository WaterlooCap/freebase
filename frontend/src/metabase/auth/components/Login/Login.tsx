import { useMount } from "react-use";
import { t } from "ttag";
import _ from "underscore";

import { usePageTitle } from "metabase/hooks/use-page-title";
import type { AuthProvider } from "metabase/plugins/types";
import { useSelector } from "metabase/redux";
import { prefetchPage, useParams, useSearchParams } from "metabase/router";
import { getApplicationName } from "metabase/selectors/whitelabel";
import { Box, Divider, Stack, Text, Title } from "metabase/ui";

import { getAuthProviders } from "../../selectors";
import { AuthLayout } from "../AuthLayout";

type LoginQueryParams = {
  provider: string;
};

export const Login = (): JSX.Element => {
  const [searchParams] = useSearchParams();
  const params = useParams<LoginQueryParams>();
  const providers = useSelector(getAuthProviders);
  const selection = getSelectedProvider(providers, params.provider);
  const redirectUrl = searchParams.get("redirect") ?? undefined;
  const applicationName = useSelector(getApplicationName);

  usePageTitle(t`Login`);

  // Signing in lands on the home page, which is a chunk of its own. Ask for it
  // while the user types, so it is there when they arrive. A login that carries
  // a redirect goes somewhere else, and that page loads on its own terms.
  useMount(() => {
    if (!redirectUrl) {
      prefetchPage("/");
    }
  });

  const [passwordProviders, otherProviders] = _.partition(
    providers,
    (provider) => provider.name === "password",
  );
  return (
    <AuthLayout>
      <Stack gap="0.5rem" ta="center">
        <Title order={1} c="text-primary" fz="1.5rem" lh="2rem">
          {t`Sign in to ${applicationName}`}
        </Title>
        {!selection && (
          <Text c="text-secondary">{t`Use your ${applicationName} account to continue.`}</Text>
        )}
      </Stack>
      {selection && selection.Panel && (
        <Box mt="2rem">
          <selection.Panel redirectUrl={redirectUrl} />
        </Box>
      )}
      {!selection && (
        <Stack mt="2rem" gap="1rem">
          {otherProviders.map((provider) => (
            <provider.Button
              key={provider.name}
              isCard={true}
              redirectUrl={redirectUrl}
            />
          ))}
          {otherProviders.length > 0 && passwordProviders.length > 0 && (
            <Divider label={t`or`} labelPosition="center" />
          )}
          {passwordProviders.map((provider) => (
            <Box key={provider.name} ta="center" fz="sm">
              <provider.Button redirectUrl={redirectUrl} />
            </Box>
          ))}
        </Stack>
      )}
    </AuthLayout>
  );
};

const getSelectedProvider = (
  providers: AuthProvider[],
  providerName?: string,
): AuthProvider | undefined => {
  const provider =
    providers.length > 1
      ? providers.find((p) => p.name === providerName)
      : providers[0];

  return provider?.Panel ? provider : undefined;
};
