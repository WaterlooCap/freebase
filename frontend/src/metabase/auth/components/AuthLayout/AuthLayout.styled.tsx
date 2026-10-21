// eslint-disable-next-line no-restricted-imports
import styled from "@emotion/styled";

import { breakpointMinSmall } from "metabase/styled-components/theme";

export const LayoutRoot = styled.div`
  position: relative;
  min-height: 100vh;
  background-color: var(--mb-color-background_page-secondary);
`;

export const LayoutBody = styled.div`
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  position: relative;
  padding: 1.5rem 1rem 4rem;
  min-height: 100vh;
`;

export const LayoutCard = styled.div`
  width: 100%;
  margin-top: 2rem;
  padding: 2rem 1.5rem;
  background-color: var(--mb-color-background_page-primary);
  border: 1px solid var(--mb-color-border-neutral);
  box-shadow: 0 2px 8px var(--mb-color-shadow-default);
  border-radius: 12px;

  ${breakpointMinSmall} {
    width: 26.5rem;
    padding: 2.5rem;
  }
`;

export const LayoutIllustration = styled.div<{
  backgroundImageSrc: string;
}>`
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background-image: ${({ backgroundImageSrc }) =>
    `url("${backgroundImageSrc}")`};
  background-size: 100% auto;
  background-repeat: no-repeat;
  background-position: right bottom;
`;
