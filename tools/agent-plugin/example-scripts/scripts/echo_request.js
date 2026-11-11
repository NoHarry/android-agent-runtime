// The host fills in ${apiKey} as it sends the request; the script never has the key, and where the echo repeats it
// the host puts the placeholder back. A host the package did not name, or a name that points into this device, is
// refused with an error the script can catch.
function attempt(request) {
  try {
    host.http(request);
    return "sent";
  } catch (e) {
    return e.message;
  }
}

function run(input) {
  const answer = host.http({
    method: "POST",
    url: "https://httpbin.org/anything?key=${apiKey}&note=" + encodeURIComponent(input.note),
    headers: { "X-Api-Key": "${apiKey}", "Content-Type": "application/json" },
    body: JSON.stringify({ note: input.note })
  });
  const seen = JSON.parse(answer.body);
  return {
    status: answer.status,
    keyInHeader: seen.headers["X-Api-Key"],
    keyInQuery: seen.args.key,
    note: seen.json && seen.json.note,
    notNamed: attempt({ url: "https://example.com/" }),
    pointsHome: attempt({ url: "https://localtest.me/" })
  };
}
