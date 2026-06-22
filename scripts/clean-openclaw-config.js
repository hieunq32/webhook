const fs = require("fs");

const configPath = process.env.HOME + "/.openclaw/openclaw.json";
const config = JSON.parse(fs.readFileSync(configPath, "utf8"));

if (config.agents && config.agents.defaults && config.agents.defaults.tools) {
  delete config.agents.defaults.tools;
}

config.tools = config.tools || {};
config.tools.profile = "minimal";

fs.writeFileSync(configPath, JSON.stringify(config, null, 2));
console.log("cleaned", configPath);
