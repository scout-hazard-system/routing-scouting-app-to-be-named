import { useCallback, useSyncExternalStore, type AnchorHTMLAttributes } from "react";

// Hand-rolled history router (same approach as the Imagoro portfolio, no dependency).
const listeners = new Set<() => void>();

function subscribe(fn: () => void): () => void {
  listeners.add(fn);
  window.addEventListener("popstate", fn);
  return () => {
    listeners.delete(fn);
    window.removeEventListener("popstate", fn);
  };
}

export function navigate(route: string): void {
  if (window.location.pathname === route) return;
  window.history.pushState(null, "", route);
  for (const fn of [...listeners]) fn();
  window.scrollTo(0, 0);
}

export function useRoute(): string {
  return useSyncExternalStore(subscribe, () => window.location.pathname.replace(/\/+$/, "") || "/");
}

export function Link({ to, ...rest }: AnchorHTMLAttributes<HTMLAnchorElement> & { to: string }) {
  const onClick = useCallback(
    (e: React.MouseEvent<HTMLAnchorElement>) => {
      if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
      e.preventDefault();
      navigate(to);
    },
    [to]
  );
  return <a href={to} onClick={onClick} {...rest} />;
}
