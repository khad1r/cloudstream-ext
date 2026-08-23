const fs = require('fs');
const path = require('path');
const vm = require('vm');
const { execSync } = require('child_process');

const anoboyPath = path.join(__dirname, 'src', 'main', 'kotlin', 'com', 'Anoboy', 'Anoboy.kt');
const extractorPath = path.join(__dirname, 'src', 'main', 'kotlin', 'com', 'Anoboy', 'Extractor.kt');
const gradlePath = path.join(__dirname, 'build.gradle.kts');

function getExistingAnoboyDomain() {
    if (fs.existsSync(anoboyPath)) {
        const content = fs.readFileSync(anoboyPath, 'utf8');
        const match = content.match(/override\s+var\s+mainUrl\s*=\s*"([^"]+)"/);
        if (match && match[1]) return match[1];
    }
    return "https://anoboy.xyz";
}

function getExistingGofileSalt() {
    if (fs.existsSync(extractorPath)) {
        const content = fs.readFileSync(extractorPath, 'utf8');
        const match = content.match(/val\s+raw\s*=\s*"[^"]+::([a-f0-9]+)"/);
        if (match && match[1]) return match[1];
    }
    return "12af056dacea0b";
}

async function fetchWithFallback(url) {
    try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 8000);
        const res = await fetch(url, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
                'Accept': '*/*',
                'Referer': 'https://gofile.io/'
            },
            signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.ok) {
            return await res.text();
        }
    } catch (e) {
        // Fallback to curl if native fetch fails or is blocked by Cloudflare
    }

    try {
        const cmd = `curl.exe -k -s -L "${url}" -H "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36" -H "Referer: https://gofile.io/"`;
        const output = execSync(cmd, { encoding: 'utf8', timeout: 10000 });
        if (output && output.length > 500 && !output.includes('404 Not Found')) {
            return output;
        }
    } catch (e) {
        console.warn(`curl fallback failed for ${url}: ${e.message}`);
    }
    return null;
}

async function getAnoboyDomain() {
    const existing = getExistingAnoboyDomain();
    const candidateUrls = Array.from(new Set([
        existing,
        "https://anoboy.xyz",
        "https://anoboy.be",
        "https://anoboy.ninja",
        "https://anoboy.live"
    ]));

    for (const url of candidateUrls) {
        try {
            console.log(`Checking Anoboy redirect for: ${url}`);
            const controller = new AbortController();
            const timeoutId = setTimeout(() => controller.abort(), 6000);
            const res = await fetch(url, { method: 'HEAD', redirect: 'follow', signal: controller.signal });
            clearTimeout(timeoutId);
            const finalUrl = new URL(res.url);
            const domain = `${finalUrl.protocol}//${finalUrl.hostname}`;
            console.log(`Found active Anoboy domain: ${domain}`);
            return domain;
        } catch (e) {
            console.warn(`Failed to connect to ${url}: ${e.message}`);
        }
    }
    console.warn(`Could not reach any Anoboy domain. Falling back to latest valid domain: ${existing}`);
    return existing;
}

async function getGofileSalt() {
    const existingSalt = getExistingGofileSalt();
    const urls = [
        "https://gofile.io/js/wt.obf.js",
        "https://gofile.io/dist/js/wt.obf.js"
    ];
    for (const url of urls) {
        try {
            console.log(`Fetching Gofile script: ${url}...`);
            const code = await fetchWithFallback(url);
            if (!code) continue;

            let modifiedCode = code;
            if (code.includes('function _sha256(')) {
                modifiedCode = code.replace(/function\s+_sha256\s*\(\s*(\w+)\s*\)\s*\{/, 'function _sha256($1){ return $1; } function _old_sha256($1){');
            } else {
                const shaConst = code.match(/0x428a2f98|1116352408/i);
                if (shaConst) {
                    const pre = code.substring(0, shaConst.index);
                    const funcs = [...pre.matchAll(/function\s+([a-zA-Z0-9_$]+)\s*\(\s*([a-zA-Z0-9_$]+)\s*\)\s*\{/g)];
                    for (let i = funcs.length - 1; i >= 0; i--) {
                        const fn = funcs[i];
                        if (fn[1].toLowerCase().includes('sha') || fn[1] === '_sha256') {
                            modifiedCode = code.replace(fn[0], `function ${fn[1]}(${fn[2]}){ return ${fn[2]}; } function _old_${fn[1]}(${fn[2]}){`);
                            break;
                        }
                    }
                }
            }

            const sandbox = {
                navigator: {
                    userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    language: "en-US"
                },
                Math,
                Date,
                parseInt,
                String,
                Array,
                decodeURIComponent,
                console
            };
            vm.createContext(sandbox);
            vm.runInContext(modifiedCode, sandbox);

            if (typeof sandbox.generateWT === 'function') {
                const rawString = sandbox.generateWT("myToken");
                const parts = rawString.split("::");
                const salt = parts[parts.length - 1];
                if (salt && salt.length >= 6) {
                    console.log(`Extracted Gofile salt: ${salt}`);
                    return salt;
                }
            }
        } catch (e) {
            console.warn(`Failed to extract Gofile salt from ${url}: ${e.message}`);
        }
    }
    console.warn(`Failed to extract Gofile salt from all URLs, falling back to latest valid salt: ${existingSalt}`);
    return existingSalt;
}

async function updateConstants() {
    const anoboyDomain = await getAnoboyDomain();
    const gofileSalt = await getGofileSalt();

    // 1. Update Anoboy.kt
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
