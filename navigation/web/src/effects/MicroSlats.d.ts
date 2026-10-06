import type { CSSProperties, ReactElement } from "react";

export type MicroSlatsPreset = "swell" | "tide" | "storm" | "signal";

export interface MicroSlatsProps {
  preset?: MicroSlatsPreset;
  color?: string;
  glintColor?: string;
  backgroundColor?: string;
  slatWidth?: number;
  slatHeight?: number;
  gap?: number;
  roundness?: number;
  scale?: number;
  speed?: number;
  direction?: number;
  chop?: number;
  stretch?: number;
  glint?: number;
  contrast?: number;
  perspective?: number;
  fog?: number;
  interactive?: boolean;
  cursorStrength?: number;
  cursorSize?: number;
  swirl?: number;
  trail?: number;
  lean?: number;
  paused?: boolean;
  className?: string;
  style?: CSSProperties;
}

declare function MicroSlats(props: MicroSlatsProps): ReactElement | null;

export default MicroSlats;
