import type { CSSProperties, ReactNode } from "react";

/** As quatro marcas de registo do frame blueprint (.bp > i.corner). */
export const Corners = () => (
  <>
    <i className="corner tl" />
    <i className="corner tr" />
    <i className="corner bl" />
    <i className="corner br" />
  </>
);

export function Bp({
  children, className = "", style, onClick,
}: {
  children: ReactNode; className?: string; style?: CSSProperties; onClick?: () => void;
}) {
  return (
    <div className={`bp ${className}`.trim()} style={style} onClick={onClick}>
      <Corners />
      {children}
    </div>
  );
}
