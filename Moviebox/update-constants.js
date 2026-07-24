const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const defaultSecretKey = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O";
const hostPool = [
    "https://api6.aoneroom.com",
    "https://api5.aoneroom.com",
    "https://api4.aoneroom.com",
    "https://api4sg.aoneroom.com",
    "https://api3.aoneroom.com",
    "https://api.inmoviebox.com"
];

async function fetchLatestSecretKey() {
    try {
        console.log("Fetching latest secret key from MovieBox-Tui repository...");
        const res = await fetch("https://raw.githubusercontent.com/mesamirh/MovieBox-Tui/main/src/providers/moviebox/crypto.rs");
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const text = await res.text();
        const match = text.match(/SECRET_KEY_DEFAULT:\s*&str\s*=\s*"([^"]+)"/);
        if (match && match[1]) {
            console.log(`Extracted latest secret key: ${match[1]}`);
            return match[1];
        }
    } catch (e) {
        console.warn(`Failed to fetch upstream secret key: ${e.message}. Using default fallback.`);
    }
    return defaultSecretKey;
}

function generateSignature(method, fullUrl, secretKeyB64, ts) {
    const parsed = new URL(fullUrl);
    const pathStr = parsed.pathname;
    const searchParams = new URLSearchParams(parsed.search);
    searchParams.sort();
    const sortedQuery = searchParams.toString();
    const canonicalUrl = sortedQuery ? `${pathStr}?${sortedQuery}` : pathStr;

    const canonicalString = [
        method.toUpperCase(),
        "application/json",
        "application/json",
        "",
        ts.toString(),
        "",
        canonicalUrl
    ].join("\n");

    let padded = secretKeyB64;
    const padding = (4 - padded.length % 4) % 4;
    if (padding > 0) padded += "=".repeat(padding);
    const keyBytes = Buffer.from(padded, 'base64');

    const hmac = crypto.createHmac('md5', keyBytes);
    hmac.update(Buffer.from(canonicalString, 'utf-8'));
    const sigB64 = hmac.digest('base64');

    return `${ts}|2|${sigB64}`;
}

async function checkHost(url, secretKey) {
    try {
        const ts = Date.now();
        const fullUrl = `${url}/wefeed-mobile-bff/tab-operating?page=1&tabId=0&version=`;
        const signature = generateSignature('GET', fullUrl, secretKey, ts);
        const reversedTs = ts.toString().split('').reverse().join('');
        const clientToken = `${ts},${crypto.createHash('md5').update(reversedTs).digest('hex')}`;

        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 5000);

        const res = await fetch(fullUrl, {
            method: 'GET',
            headers: {
                'User-Agent': 'com.community.oneroom/50020045 (Linux; U; Android 11; en_US; Redmi; Build/RP1A.200720.011; Cronet/135.0.7012.3)',
                'Accept': 'application/json',
                'Content-Type': 'application/json',
                'Connection': 'keep-alive',
                'X-Client-Token': clientToken,
                'x-tr-signature': signature,
                'X-Client-Info': '{"package_name":"com.community.oneroom","version_name":"3.0.03.0529.03","version_code":50020045,"os":"android","os_version":"11","install_ch":"ps","device_id":"8a9f3b2c1d4e5f6a7b8c9d0e1f2a3b4c","install_store":"ps","gaid":"12345678-1234-1234-1234-123456789abc","brand":"Redmi","model":"2201117TY","system_language":"en","net":"NETWORK_WIFI","region":"US","timezone":"America/New_York","sp_code":"40401","X-Play-Mode":"2"}',
                'X-Client-Status': '0',
                'X-Forwarded-For': '103.241.12.34'
            },
            signal: controller.signal
        });
        clearTimeout(timeout);
        console.log(`Host ${url} responded with HTTP ${res.status}`);
        return res.status < 400;
    } catch (e) {
        console.warn(`Failed to connect to ${url}: ${e.message}`);
        return false;
    }
}

async function updateMovieboxConstants() {
    const secretKey = await fetchLatestSecretKey();
    const activeHosts = [];

    for (const host of hostPool) {
        if (await checkHost(host, secretKey)) {
            activeHosts.push(host);
        }
    }

    if (activeHosts.length === 0) {
        console.error("No active Moviebox hosts found!");
        return;
    }

    const primaryHost = activeHosts[0];
    console.log(`Primary active Moviebox host: ${primaryHost}`);

    const movieboxPath = path.join(__dirname, 'src', 'main', 'kotlin', 'com', 'Moviebox', 'Moviebox.kt');
    if (fs.existsSync(movieboxPath)) {
        let content = fs.readFileSync(movieboxPath, 'utf8');
        content = content.replace(
            /(override\s+var\s+mainUrl\s*=\s*")[^"]+(")/,
            `$1${primaryHost}$2`
        );
        content = content.replace(
            /(private\s+var\s+secretKey\s*=\s*")[^"]+(")/,
            `$1${secretKey}$2`
        );
        fs.writeFileSync(movieboxPath, content, 'utf8');
        console.log(`Updated Moviebox.kt mainUrl: ${primaryHost}, secretKey: ${secretKey}`);
    } else {
        console.warn(`Moviebox.kt not found at: ${movieboxPath}`);
    }
}

updateMovieboxConstants();
