/**
 * Clipboard access with a no-API fallback. The helper node uses a CSS class
 * rather than inline styles, keeping the page compatible with a strict CSP.
 *
 * Uses Promise.then instead of async/await because HtmlUnit's Rhino-based
 * parser does not accept async function syntax in the production bundle.
 */
export function copyText(value: string): Promise<boolean> {
  if (navigator.clipboard?.writeText) {
    return navigator.clipboard.writeText(value).then(
      () => true,
      () => fallbackCopy(value),
    );
  }
  return Promise.resolve(fallbackCopy(value));
}

function fallbackCopy(value: string): boolean {
  try {
    const helper = document.createElement('textarea');
    helper.value = value;
    helper.setAttribute('readonly', '');
    helper.className = 'clipboard-helper';
    document.body.append(helper);
    helper.select();
    const copied = document.execCommand('copy');
    helper.remove();
    return copied;
  } catch {
    return false;
  }
}
