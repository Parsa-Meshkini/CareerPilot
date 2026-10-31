// Runs inside the page (injected on demand by the popup). Reads tables, label/value pairs, schema.org JSON-LD,
// job cards and plain "Label: value" text, and returns them as plain data. Nothing is sent from here.
(() => {
  const text = (el) => (el ? el.innerText || el.textContent || "" : "");
  const tables = [...document.querySelectorAll("table")].map((t) => {
    let headers = [...t.querySelectorAll("thead th, tr:first-child th")].map(text);
    const allRows = [...t.querySelectorAll("tr")];
    let rows = allRows.filter((tr) => tr.querySelectorAll("td").length);
    if (!headers.length && rows.length > 1) {          // header row written with <td> cells
      headers = [...rows[0].querySelectorAll("td")].map(text);
      rows = rows.slice(1);
    }
    return { headers, rows: rows.map((tr) => ({ cells: [...tr.querySelectorAll("td")].map(text), url: tr.querySelector("a[href]")?.href || "" })) };
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

  const jsonld = [];
  document.querySelectorAll('script[type="application/ld+json"]').forEach((s) => { try { jsonld.push(JSON.parse(s.textContent)); } catch (e) { /* ignore bad JSON */ } });

  const cards = [...document.querySelectorAll('[class*="job"], [class*="posting"], [class*="result"], article')]
    .filter((el) => el.querySelector("h1, h2, h3, h4") && !el.querySelector('[class*="job"] [class*="job"]'))
    .map((el) => ({
      title: text(el.querySelector("h1, h2, h3, h4")),
      company: text(el.querySelector('[class*="company"], [class*="employer"], [class*="organization"], [class*="org"]')),
      location: text(el.querySelector('[class*="location"], [class*="city"]')),
      deadline: text(el.querySelector('[class*="deadline"], [class*="closing"], time')),
      url: el.querySelector("a[href]")?.href || "",
      text: text(el),
    }));

  const meta = (n) => document.querySelector(`meta[property="${n}"], meta[name="${n}"]`)?.content || "";
  const description = text(document.querySelector("#jobDescription, .job-description, main, article") || document.body);
  return { tables, pairs, jsonld, cards, description, url: location.href,
           h1: text(document.querySelector("h1")), ogTitle: meta("og:title"), siteName: meta("og:site_name"), pageTitle: document.title };
})();
