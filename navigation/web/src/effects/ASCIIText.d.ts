import type { CSSProperties, ReactElement } from "react";

export interface ASCIITextProps {
  text?: string;
  enableWaves?: boolean;
  enableHueShift?: boolean;
  asciiFontSize?: number;
  textFontSize?: number;
  textColor?: string;
  planeBaseHeight?: number;
  className?: string;
  style?: CSSProperties;
}

declare function ASCIIText(props: ASCIITextProps): ReactElement;

export default ASCIIText;
