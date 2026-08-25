/** SVGs inline copiados do design — mesmos paths, mesmos stroke-widths. */

type P = { size?: number; color?: string; width?: number; className?: string };

const base = (s: number, c: string, w: number) => ({
  width: s, height: s, viewBox: "0 0 24 24",
  fill: "none", stroke: c, strokeWidth: w,
});

export const IconLogo = ({ size = 21, color = "#94bce3", width = 1.5, className }: P) => (
  <svg {...base(size, color, width)} className={className}>
    <rect x="3" y="3" width="18" height="18" />
    <path d="M3 9h18M9 21V9" />
  </svg>
);

export const IconScan = ({ size = 18, color = "#416180", width = 1.5 }: P) => (
  <svg {...base(size, color, width)}>
    <rect x="3" y="5" width="18" height="14" />
    <path d="M7 9v6M11 9v6M15 9v6" />
  </svg>
);

export const IconPlus = ({ size = 26, color = "currentColor", width = 1.5, className }: P) => (
  <svg {...base(size, color, width)} className={className}>
    <path d="M12 5v14M5 12h14" />
  </svg>
);

export const IconPasso = ({ size = 26, color = "currentColor", width = 1.5, className }: P) => (
  <svg {...base(size, color, width)} className={className}>
    <path d="M5 3v18l4-3 4 3 4-3 2 1.5V3z" />
    <path d="M9 8h6M9 12h6" />
  </svg>
);

export const IconCheckBox = ({ size = 26, color = "currentColor", width = 1.5, className }: P) => (
  <svg {...base(size, color, width)} className={className}>
    <path d="M9 11l3 3 8-8" />
    <path d="M20 12v7H4V5h11" />
  </svg>
);

export const IconInspecao = ({ size = 26, color = "currentColor", width = 1.5, className }: P) => (
  <svg {...base(size, color, width)} className={className}>
    <circle cx="10.5" cy="10.5" r="6.5" />
    <path d="m20 20-4.9-4.9M8 10.5h5M10.5 8v5" />
  </svg>
);

export const IconBusca = ({ size = 26, color = "currentColor", width = 1.5 }: P) => (
  <svg {...base(size, color, width)}>
    <circle cx="11" cy="11" r="7" />
    <path d="m21 21-4.3-4.3" />
  </svg>
);

export const IconProcessos = ({ size = 26, color = "currentColor", width = 1.5 }: P) => (
  <svg {...base(size, color, width)}>
    <path d="M12 2v4M12 18v4M2 12h4M18 12h4M5 5l2.5 2.5M16.5 16.5 19 19M19 5l-2.5 2.5M7.5 16.5 5 19" />
    <circle cx="12" cy="12" r="3.5" />
  </svg>
);

export const IconCargas = ({ size = 26, color = "currentColor", width = 1.5 }: P) => (
  <svg {...base(size, color, width)}>
    <rect x="3" y="8" width="18" height="12" />
    <path d="M3 12h18M8 8V5h8v3" />
  </svg>
);

export const IconCheck = ({ size = 13, color = "currentColor", width = 3 }: P) => (
  <svg {...base(size, color, width)}>
    <path d="M20 6 9 17l-5-5" />
  </svg>
);

export const IconSeta = ({ size = 20, color = "#5980a6", width = 1.6 }: P) => (
  <svg {...base(size, color, width)}>
    <path d="M5 12h14M13 6l6 6-6 6" />
  </svg>
);
