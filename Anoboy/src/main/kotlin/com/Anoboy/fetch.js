/* https://gofile.io/dist/js/config.js */
const appdata = {};
appdata.accounts = {};
appdata.wt = "4fd6sg89d7s6";
appdata.apiServer = "api";

/* https://gofile.io/dist/js/account.js */
async function getAccountActive() {
  // Create a guest account when no accounts exist
  // return '3oIR68HprTX0smJpCWKnkbFopwCN0XpR'
  try {
    const response = await fetch(
      "https://" + appdata.apiServer + ".gofile.io/accounts",
      { method: "POST" },
    );

    if (!response.ok) {
      throw new Error(response.status);
    }

    const result = await response.json();

    if (result.status !== "ok") {
      throw new Error(result.status);
    }

    return result.data;
  } catch (error) {
    throw new Error("getAccountActive " + error.message);
  }
}
/* https://gofile.io/dist/js/wt.obf.js */
const crypto = require("crypto");

function sha256(str) {
  return crypto.createHash("sha256").update(str).digest("hex");
}

function generateWT(token) {
  const userAgent =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
  const language = "en-US";
  const timeSlot = Math.floor(Date.now() / 1000 / 3600 / 4).toString();
  const raw = `${userAgent}::${language}::${token}::${timeSlot}::9844d94d963d30`;
  return sha256(raw);
}

/* https://gofile.io/dist/js/filemanager/api.js */
async function getContent(
  contentId,
  contentFilter = "",
  page = 1,
  pageSize = 1000,
  sortField = "createTime",
  sortDirection = -1,
) {
  try {
    const accountActive = await getAccountActive();
    const url = new URL(
      `https://${appdata.apiServer}.gofile.io/contents/${contentId}`,
    );
    const params = new URLSearchParams({
      contentFilter,
      page,
      pageSize,
      sortField,
      sortDirection,
    });

    // const password = sessionStorage.getItem(`password|${contentId}`);
    // if (password) params.append("password", password);

    url.search = params.toString();

    const response = await fetch(url, {
      headers: {
        Authorization: `Bearer ${accountActive.token}`,
        "X-Website-Token": generateWT(accountActive.token),
        "X-BL": navigator.language || "",
      },
    });

    if (!response.ok) throw new Error(response.status);

    const fetchResult = await response.json();
    if (
      fetchResult.status !== "ok" &&
      fetchResult.status !== "error-notFound"
    ) {
      throw new Error(fetchResult.status);
    }

    if (
      fetchResult.data.password &&
      fetchResult.data.passwordStatus == "passwordWrong"
    ) {
      sessionStorage.removeItem(`password|${contentId}`);
    }

    return fetchResult;
  } catch (error) {
    throw new Error("getContent " + error.message);
  }
}
(async (_) => {
  var url = "https://gofile.io/d/nKWrOT";
  var content = await getContent(url.split("/").pop());
  console.log(content);
})();
