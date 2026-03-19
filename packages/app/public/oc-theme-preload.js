;(function () {
  var key = "opencode-theme-id"
  var themeId = localStorage.getItem(key) || "oc-2"

  if (themeId === "oc-1") {
    themeId = "oc-2"
    localStorage.setItem(key, themeId)
    localStorage.removeItem("opencode-theme-css-light")
    localStorage.removeItem("opencode-theme-css-dark")
  }

  var scheme = localStorage.getItem("opencode-color-scheme") || "system"
  var isDark = scheme === "dark" || (scheme === "system" && matchMedia("(prefers-color-scheme: dark)").matches)
  var mode = isDark ? "dark" : "light"
  var fill = isDark ? "#131010" : "#F8F7F7"

  document.documentElement.dataset.theme = themeId
  document.documentElement.dataset.colorScheme = mode

  var css = themeId === "oc-2" ? null : localStorage.getItem("opencode-theme-css-" + mode)
  if (css) {
    var match = css.match(/--background-base\s*:\s*([^;]+)\s*;/)
    if (match && match[1]) {
      fill = match[1].trim()
    }
  }

  var tags = document.querySelectorAll('meta[name="theme-color"]')
  if (tags.length) {
    for (var i = 0; i < tags.length; i++) {
      tags[i].setAttribute("content", fill)
    }
  }
  if (!tags.length) {
    var tag = document.createElement("meta")
    tag.name = "theme-color"
    tag.content = fill
    document.head.appendChild(tag)
  }

  if (themeId === "oc-2") return

  if (css) {
    var style = document.createElement("style")
    style.id = "oc-theme-preload"
    style.textContent =
      ":root{color-scheme:" +
      mode +
      ";--text-mix-blend-mode:" +
      (isDark ? "plus-lighter" : "multiply") +
      ";" +
      css +
      "}"
    document.head.appendChild(style)
  }
})()
