// The weather now at a place, from Open-Meteo (no key needed). host.http answers {status, headers, body}.
function run(input) {
  const url = "https://api.open-meteo.com/v1/forecast?latitude=" + input.latitude + "&longitude=" + input.longitude +
    "&current=temperature_2m,wind_speed_10m,weather_code";
  const answer = host.http({ url: url });
  if (answer.status !== 200) throw new Error("Open-Meteo answered " + answer.status);
  const current = JSON.parse(answer.body).current;
  host.log("weather at " + input.latitude + ", " + input.longitude);
  return {
    temperature: current.temperature_2m,
    windSpeed: current.wind_speed_10m,
    weatherCode: current.weather_code,
    time: current.time
  };
}
