export function readingKey(event: KeyboardEvent): string | undefined {
  if (event.defaultPrevented || event.repeat || event.isComposing || event.ctrlKey || event.metaKey || event.altKey || event.shiftKey
    || document.visibilityState === 'hidden' || window.getSelection()?.toString()) return
  const target = event.target
  if (target instanceof Element && target.closest('input, textarea, select, button, a, summary, [contenteditable]:not([contenteditable="false"]), [role="textbox"], [role="button"], [role="combobox"], [role="dialog"], [data-reading-no-shortcuts]')) return
  if (document.querySelector('dialog[open], [aria-modal="true"]')) return
  const key = event.key.toLowerCase()
  return [' ', 'h', '1', '2', '3'].includes(key) ? key : undefined
}
