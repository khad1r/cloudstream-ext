const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { execSync } = require('child_process');

const movieboxPath = path.join(__dirname, 'src', 'main', 'kotlin', 'com', 'Moviebox', 'Moviebox.kt');

function getExistingMovieboxConstants() {
    let mainUrl = "https://api6.aoneroom.com";
    let secretKey = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O";
    let hostPool = [
        "https://api6.aoneroom.com",
        "https://api5.aoneroom.com",
        "https://api4.aoneroom.com",
        "https://api4sg.aoneroom.com",
        "https://api3.aoneroom.com",
        "https://api6sg.aoneroom.com",
        "https://api.inmoviebox.com"
    ];

    if (fs.existsSync(movieboxPath)) {
        const content = fs.readFileSync(movieboxPath, 'utf8');
        const mainUrlMatch = content.match(/override\s+var\s+mainUrl\s*=\s*"([^"]+)"/);
        if (mainUrlMatch && mainUrlMatch[1]) mainUrl = mainUrlMatch[1];

        const secretKeyMatch = content.match(/private\s+var\s+secretKey\s*=\s*"([^"]+)"/);
        if (secretKeyMatch && secretKeyMatch[1]) secretKey = secretKeyMatch[1];

        const hostPoolMatch = content.match(/private\s+val\s+hostPool\s*=\s*listOf\(([\s\S]*?)\)/);
        if (hostPoolMatch && hostPoolMatch[1]) {
            const parsedHosts = hostPoolMatch[1]
                .split('\n')
                .map(line => line.trim().replace(/^"|",?$|^r#"/g, '').replace(/"/g, ''))
                .filter(s => s.startsWith("http"));
            if (parsedHosts.length > 0) hostPool = parsedHosts;
        }
    }
    return { mainUrl, secretKey, hostPool };
}

async function fetchWithFallback(url) {
    try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 8000);
        const res = await fetch(url, { signal: controller.signal });
        clearTimeout(timeoutId);
        if (res.ok) {
            return await res.text();
        }
    } catch (e) {
        // Fallback to curl
    }

    try {
        const cmd = `curl.exe -k -s -L "${url}"`;
        const output = execSync(cmd, { encoding: 'utf8', timeout: 10000 });
        if (output && output.length > 0 && !output.includes('404: Not Found')) {
            return output;
        }
    } catch (e) {
        console.warn(`curl fallback failed for ${url}: ${e.message}`);
    }
    return null;
}

async function fetchLatestSecretKey(existingSecretKey) {
    try {
        console.log("Fetching latest secret key from MovieBox-Tui repository...");
        const text = await fetchWithFallback("https://raw.githubusercontent.com/mesamirh/MovieBox-Tui/main/src/providers/moviebox/crypto.rs");
        if (text) {
            const match = text.match(/SECRET_KEY_DEFAULT:\s*&str\s*=\s*"([^"]+)"/);
            if (match && match[1]) {
                console.log(`Extracted latest secret key: ${match[1]}`);
                return match[1];
            }
        }
    } catch (e) {
        console.warn(`Failed to fetch upstream secret key: ${e.message}.`);
    }
    console.warn(`Using latest valid secret key fallback: ${existingSecretKey}`);
    return existingSecretKey;
}

async function fetchLatestHostPool(existingHostPool) {
    try {
        console.log("Fetching latest host pool from MovieBox-Tui repository...");
        const text = await fetchWithFallback("https://raw.githubusercontent.com/mesamirh/MovieBox-Tui/main/src/providers/moviebox/client.rs");
        if (text) {
            const match = text.match(/const\s+HOST_POOL:\s*&\[&str\]\s*=\s*&\[([\s\S]*?)\];/);
            if (match && match[1]) {
                const hosts = match[1]
                    .split('\n')
                    .map(line => line.trim().replace(/^"|",?$|^r#"/g, '').replace(/"/g, ''))
                    .filter(s => s.startsWith("http"));
                if (hosts.length > 0) {
                    const combined = Array.from(new Set([...hosts, ...existingHostPool]));
                    console.log(`Extracted ${hosts.length} hosts from upstream client.rs (combined total: ${combined.length}):`, combined);
                    return combined;
                }
            }
        }
    } catch (e) {
        console.warn(`Failed to fetch upstream host pool: ${e.message}.`);
    }
    console.warn(`Using latest valid host pool fallback (${existingHostPool.length} hosts).`);
    return existingHostPool;
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
        try {
            const ts = Date.now();
            const fullUrl = `${url}/wefeed-mobile-bff/tab-operating?page=1&tabId=0&version=`;
            const signature = generateSignature('GET', fullUrl, secretKey, ts);
            const reversedTs = ts.toString().split('').reverse().join('');
            const clientToken = `${ts},${crypto.createHash('md5').update(reversedTs).digest('hex')}`;
            const clientInfo = '{"package_name":"com.community.oneroom","version_name":"3.0.03.0529.03","version_code":50020045,"os":"android","os_version":"11","install_ch":"ps","device_id":"8a9f3b2c1d4e5f6a7b8c9d0e1f2a3b4c","install_store":"ps","gaid":"12345678-1234-1234-1234-123456789abc","brand":"Redmi","model":"2201117TY","system_language":"en","net":"NETWORK_WIFI","region":"US","timezone":"America/New_York","sp_code":"40401","X-Play-Mode":"2"}';
            const cmd = `curl.exe -k -s -o /dev/null -w "%{http_code}" -X GET "${fullUrl}" -H "User-Agent: com.community.oneroom/50020045" -H "X-Client-Token: ${clientToken}" -H "x-tr-signature: ${signature}" -H "X-Client-Info: ${clientInfo.replace(/"/g, '\\"')}"`;
            const statusCode = parseInt(execSync(cmd, { encoding: 'utf8', timeout: 6000 }).trim(), 10);
            console.log(`Host ${url} (curl) responded with HTTP ${statusCode}`);
            return statusCode >= 200 && statusCode < 400;
        } catch (err) {
            console.warn(`Failed to connect to ${url}: ${e.message}`);
            return false;
        }
    }
}

async function updateMovieboxConstants() {
    const existing = getExistingMovieboxConstants();
    const secretKey = await fetchLatestSecretKey(existing.secretKey);
    const hostPool = await fetchLatestHostPool(existing.hostPool);
    const candidateHosts = Array.from(new Set([existing.mainUrl, ...hostPool, ...existing.hostPool]));
    const activeHosts = [];

    for (const host of candidateHosts) {
        if (await checkHost(host, secretKey)) {
            activeHosts.push(host);
        }
    }

    if (activeHosts.length === 0) {
        console.warn(`No active Moviebox hosts responded. Retaining existing valid config (mainUrl: ${existing.mainUrl}, ${existing.hostPool.length} hosts).`);
        return;
    }

    const primaryHost = activeHosts[0];
    console.log(`Primary active Moviebox host: ${primaryHost}`);

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
        const kotlinHostPoolStr = `private val hostPool = listOf(\n` +
            activeHosts.map(h => `        "${h}"`).join(',\n') +
            `\n    )`;
        content = content.replace(
            /private\s+val\s+hostPool\s*=\s*listOf\([\s\S]*?\)/,
            kotlinHostPoolStr
        );
        fs.writeFileSync(movieboxPath, content, 'utf8');
        console.log(`Updated Moviebox.kt mainUrl: ${primaryHost}, secretKey: ${secretKey}, hostPool: ${activeHosts.length} hosts`);
    } else {
        console.warn(`Moviebox.kt not found at: ${movieboxPath}`);
    }
}

updateMovieboxConstants();
