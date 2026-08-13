const fs = require('fs');
const path = require('path');
const vm = require('vm');

async function getAnoboyDomain() {
    const baseUrls = [
        "https://anoboy.xyz",
        "https://anoboy.be",
        "https://anoboy.ninja",
        "https://anoboy.live"
    ];
    for (const url of baseUrls) {
        try {
            console.log(`Checking Anoboy redirect for: ${url}`);
            const res = await fetch(url, { method: 'HEAD', redirect: 'follow' });
            const finalUrl = new URL(res.url);
            const domain = `${finalUrl.protocol}//${finalUrl.hostname}`;
            console.log(`Found active Anoboy domain: ${domain}`);
            return domain;
        } catch (e) {
            console.warn(`Failed to connect to ${url}: ${e.message}`);
        }
    }
    return "https://anoboy.xyz"; // fallback
}

async function getGofileSalt() {
    const urls = [
        "https://gofile.io/js/wt.obf.js",
        "https://gofile.io/dist/js/wt.obf.js"
    ];
    for (const url of urls) {
        try {
            console.log(`Fetching Gofile script: ${url}...`);
            const res = await fetch(url, {
                headers: {
                    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'
                }
            });
            if (!res.ok) continue;
            const code = await res.text();

            // Find the SHA-256 fractional constant (hex/decimal format) to locate the function
            const sha256Regex = /0x428a2f98|1116352408/i;
            const match = code.match(sha256Regex);
            if (!match) continue;
            const sha256Index = match.index;

            const preCode = code.substring(0, sha256Index);
            const funcMatch = [...preCode.matchAll(/function\s+(\w+)\s*\(\s*(\w+)\s*\)\s*\{/g)].pop();
            if (!funcMatch) continue;

            const funcName = funcMatch[1];
            const paramName = funcMatch[2];

            // Replace the SHA-256 hash function to return the raw string instead of hashing it
            const target = `function ${funcName}(${paramName}){`;
            const replacement = `function ${funcName}(${paramName}){ return ${paramName};`;
            const modifiedCode = code.replace(target, replacement);

            // Run the modified script in a sandbox to extract the salt
            const sandbox = {
                navigator: {
                    userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    language: "en-US"
                }
            };
            vm.createContext(sandbox);
            vm.runInContext(modifiedCode, sandbox);

            // Call the deobfuscated generateWT function
            const rawString = sandbox.generateWT("myToken");
            const parts = rawString.split("::");
            const salt = parts[parts.length - 1];
            console.log(`Extracted Gofile salt: ${salt}`);
            return salt;
        } catch (e) {
            console.warn(`Failed to extract Gofile salt from ${url}: ${e.message}`);
        }
    }
    console.error("Failed to extract Gofile salt from all URLs, using fallback.");
    return "9844d94d963d30"; // fallback to current known salt
}

async function updateConstants() {
    const anoboyDomain = await getAnoboyDomain();
    const gofileSalt = await getGofileSalt();

    // 1. Update Anoboy.kt
    const anoboyPath = path.join(__dirname, 'src', 'main', 'kotlin', 'com', 'Anoboy', 'Anoboy.kt');
    if (fs.existsSync(anoboyPath)) {
        let content = fs.readFileSync(anoboyPath, 'utf8');
        content = content.replace(
            /(override\s+var\s+mainUrl\s*=\s*")[^"]+(")/,
            `$1${anoboyDomain}$2`
        );
        fs.writeFileSync(anoboyPath, content, 'utf8');
        console.log(`Updated Anoboy.kt with domain: ${anoboyDomain}`);
    } else {
        console.warn(`Anoboy.kt not found at: ${anoboyPath}`);
    }

    // 2. Update Extractor.kt
    const extractorPath = path.join(__dirname, 'src', 'main', 'kotlin', 'com', 'Anoboy', 'Extractor.kt');
    if (fs.existsSync(extractorPath)) {
        let content = fs.readFileSync(extractorPath, 'utf8');
        content = content.replace(
            /(val\s+raw\s*=\s*"[^"]+::)[a-f0-9]+(")/,
            `$1${gofileSalt}$2`
        );
        fs.writeFileSync(extractorPath, content, 'utf8');
        console.log(`Updated Extractor.kt with Gofile salt: ${gofileSalt}`);
    } else {
        console.warn(`Extractor.kt not found at: ${extractorPath}`);
    }

    // 3. Update build.gradle.kts iconUrl
    const gradlePath = path.join(__dirname, 'build.gradle.kts');
    if (fs.existsSync(gradlePath)) {
        let content = fs.readFileSync(gradlePath, 'utf8');
        const hostname = new URL(anoboyDomain).hostname;
        const iconUrl = `https://www.google.com/s2/favicons?domain=${hostname}&sz=%size%`;
        content = content.replace(
            /(iconUrl\s*=\s*")[^"]+(")/,
            `$1${iconUrl}$2`
        );
        fs.writeFileSync(gradlePath, content, 'utf8');
        console.log(`Updated build.gradle.kts with iconUrl: ${iconUrl}`);
    }
}

updateConstants();
