const { chromium } = require('playwright');

async function main() {
  const browser = await chromium.launch({
    headless: false
  });
  await browser.close();
  console.log('chromium_headful_ok');
}

main().catch(error => {
  console.error(error.message);
  process.exit(1);
});
