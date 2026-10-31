// Runs inside the page (injected on demand by the popup). Reads tables and label/value pairs and returns them as plain data.
(() => {
  const text = (el) => (el ? el.innerText || el.textContent || "" : "");
  const tables = [...document.querySelectorAll("table")].map((t) => {
    const headers = [...t.querySelectorAll("thead th, tr:first-child th")].map(text);
    const rows = [...t.querySelectorAll("tbody tr, tr")]
      .filter((tr) => tr.querySelectorAll("td").length)
      .map((tr) => ({ cells: [...tr.querySelectorAll("td")].map(text), url: tr.querySelector("a[href]")?.href || "" }));
    return { headers, rows };
  }).filter((t) => t.headers.length && t.rows.length);

  const pairs = [];
  document.querySelectorAll("dl").forEach((dl) => {
    const dts = [...dl.querySelectorAll("dt")], dds = [...dl.querySelectorAll("dd")];
    dts.forEach((dt, i) => dds[i] && pairs.push([text(dt), text(dds[i])]));
  });
  document.querySelectorAll("tr").forEach((tr) => {
    const th = tr.querySelector("th"), td = tr.querySelector("td");
    if (th && td && tr.querySelectorAll("td").length === 1) pairs.push([text(th), text(td)]);
  });
  const description = text(document.querySelector("#jobDescription, .job-description, main, article") || document.body);
  return { tables, pairs, description, url: location.href };
})();
