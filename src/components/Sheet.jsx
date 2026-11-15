import { useEffect, useRef } from 'react';
import { X } from 'lucide-react';

/**
 * Bottom sheet on phones, centred dialog on wider screens.
 * Traps focus while open, closes on Escape or backdrop tap, and restores focus.
 */
export default function Sheet({ title, subtitle, onClose, children, wide = false, labelledBy }) {
  const ref = useRef(null);
  useEffect(() => {
    const previous = document.activeElement;
    const el = ref.current;
    const focusables = () => [...el.querySelectorAll('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])')].filter((n) => !n.disabled);
    (el.querySelector('[data-autofocus]') || focusables()[0] || el).focus();
    const onKey = (e) => {
      if (e.key === 'Escape') { e.stopPropagation(); onClose(); }
      if (e.key === 'Tab') {
        const f = focusables();
        if (!f.length) return;
        if (e.shiftKey && document.activeElement === f[0]) { e.preventDefault(); f[f.length - 1].focus(); }
        else if (!e.shiftKey && document.activeElement === f[f.length - 1]) { e.preventDefault(); f[0].focus(); }
      }
    };
    el.addEventListener('keydown', onKey);
    return () => {
      el.removeEventListener('keydown', onKey);
      if (previous && typeof previous.focus === 'function') previous.focus();
    };
  }, [onClose]);

  return (
    <div className="sheet-layer" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div ref={ref} className={`sheet ${wide ? 'sheet--wide' : ''}`} role="dialog" aria-modal="true" aria-labelledby={labelledBy || 'sheet-title'} tabIndex={-1}>
        <div className="sheet__grip" aria-hidden="true" />
        {(title || subtitle) && (
          <header className="sheet__head">
            <div className="sheet__titles">
              {title && <h2 id="sheet-title" className="sheet__title">{title}</h2>}
              {subtitle && <p className="sheet__sub">{subtitle}</p>}
            </div>
            <button type="button" className="icon-btn icon-btn--sm" onClick={onClose} aria-label="Close" title="Close"><X size={18} /></button>
          </header>
        )}
        <div className="sheet__body">{children}</div>
      </div>
    </div>
  );
}
