import { createContext, useCallback, useContext, useRef, useState } from 'react';

const ToastContext = createContext(() => {});

export function ToastProvider({ children }) {
  const [toast, setToast] = useState(null);
  const timer = useRef(null);
  const show = useCallback((message, { tone = 'info', action = null, ms } = {}) => {
    clearTimeout(timer.current);
    setToast({ message, tone, action, id: Date.now() });
    timer.current = setTimeout(() => setToast(null), ms || (tone === 'error' ? 5000 : 2800));
  }, []);
  return (
    <ToastContext.Provider value={show}>
      {children}
      <div className="toast-region" role="status" aria-live="polite">
        {toast && (
          <div key={toast.id} className={`toast toast--${toast.tone}`}>
            <span>{toast.message}</span>
            {toast.action && (
              <button type="button" className="toast__action" onClick={() => { toast.action.onClick(); setToast(null); }}>
                {toast.action.label}
              </button>
            )}
          </div>
        )}
      </div>
    </ToastContext.Provider>
  );
}

export const useToast = () => useContext(ToastContext);
