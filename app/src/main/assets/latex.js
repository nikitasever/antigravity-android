function renderLatex(encodedLatex, textColor) {
  const maxWidth = window.innerWidth;
  document.body.style.color = textColor;
  const latex = decodeURIComponent(encodedLatex);

  let container = document.querySelector(".latex-container");
  if (container == null) {
    container = document.createElement("span");
    container.className = "latex-container";
    container.style.display = "inline-block";
    container.style.whiteSpace = "nowrap";
    const isRtl =
      getComputedStyle(document.documentElement).direction === "rtl";
    container.style.transformOrigin = isRtl ? "top right" : "top left";
    document.body.appendChild(container);

    const resizeObserver = new ResizeObserver((entries) => {
      for (let entry of entries) {
        const width = entry.contentRect.width;
        if (width > maxWidth) {
          container.style.transform = `scale(${maxWidth / width})`;
        }
        window.Android.onSizeChanged(
          Math.min(entry.contentRect.width, maxWidth),
          entry.contentRect.height
        );
      }
    });
    resizeObserver.observe(container);
  }

  katex.render(latex, container, {
    throwOnError: true,
  });

  const { width, height } = container.getBoundingClientRect();
  if (width > maxWidth) {
    container.style.transform = `scale(${maxWidth / width})`;
  }
  window.Android.onRenderCompleted(Math.min(width, maxWidth), height);
  // Above, we let katex render the latex with whatever size it wants.
  // Once we report the size back to Android, Android will constrain the size of this view to the measured size.
  // However, sometimes, when that happens, this WebView tries to wrap the text.
  // Instead, allow the span to be as wide as it needs to be
  container.style.width = "max-content";
}
