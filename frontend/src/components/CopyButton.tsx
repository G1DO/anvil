import { useEffect, useRef, useState } from 'react';
import { copyText } from '../clipboard';
import { CheckIcon, CopyIcon } from '../icons';

interface CopyButtonProps {
  value: string;
  label: string;
}

/** Icon-only copy control: no text is added to the surrounding hash cell. */
export function CopyButton({ value, label }: CopyButtonProps) {
  const [copied, setCopied] = useState(false);
  const timer = useRef<number | undefined>(undefined);

  useEffect(
    () => () => {
      if (timer.current !== undefined) {
        window.clearTimeout(timer.current);
      }
    },
    [],
  );

  const handleCopy = (): void => {
    void copyText(value).then((ok) => {
      if (!ok) {
        return;
      }
      setCopied(true);
      if (timer.current !== undefined) {
        window.clearTimeout(timer.current);
      }
      timer.current = window.setTimeout(() => setCopied(false), 1600);
    });
  };

  return (
    <button
      type="button"
      className={`icon-button copy-button${copied ? ' is-copied' : ''}`}
      aria-label={copied ? `${label} (copied)` : label}
      title={copied ? 'Copied' : label}
      onClick={handleCopy}
    >
      {copied ? <CheckIcon className="icon icon-sm" /> : <CopyIcon className="icon icon-sm" />}
    </button>
  );
}
