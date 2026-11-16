/**
 * Franxit's anonymous usage counter, a Google Apps Script for a Google Sheet.
 *
 * Setup (once): create a Sheet, then Extensions > Apps Script, paste this file, run `setup`, and
 * Deploy > New deployment > Web app (Execute as: Me, Who has access: Anyone). Put the web app URL
 * in Usage.kt (ENDPOINT).
 *
 * The app sends {id, version, opens} when it's opened: a random number made on the phone, the app
 * version and how many times the app was opened. One row per installation in the sheet "Installs";
 * the sheet "Overview" sums it up. Only the owner of the Sheet sees any of it.
 */

var INSTALLS = 'Installs';
var HEADER = ['Install', 'First seen', 'Last seen', 'Opens', 'Version'];

function doPost(e) {
  var lock = LockService.getScriptLock();
  try {
    var d = JSON.parse(e.postData.contents);
    var id = String(d.id || '');
    // Only what the app sends: a random id of the form 8-4-4-4-12 hex digits.
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id)) return ok();
    var opens = Math.max(0, Math.min(Number(d.opens) || 0, 1000000));
    var version = String(d.version || '').slice(0, 20);

    lock.waitLock(10000);
    var sheet = installsSheet();
    var last = sheet.getLastRow();
    var ids = last > 1 ? sheet.getRange(2, 1, last - 1, 1).getValues().map(function (r) { return r[0]; }) : [];
    var row = ids.indexOf(id);
    var now = new Date();
    if (row === -1) {
      sheet.appendRow([id, now, now, opens, version]);
    } else {
      sheet.getRange(row + 2, 3, 1, 3).setValues([[now, opens, version]]);
    }
  } catch (err) {
    // A bad request is just ignored.
  } finally {
    try { lock.releaseLock(); } catch (err2) {}
  }
  return ok();
}

function ok() {
  return ContentService.createTextOutput('ok');
}

function installsSheet() {
  var ss = SpreadsheetApp.getActive();
  var sheet = ss.getSheetByName(INSTALLS) || ss.insertSheet(INSTALLS);
  if (sheet.getLastRow() === 0) {
    sheet.appendRow(HEADER);
    sheet.getRange('A1:E1').setFontWeight('bold');
    sheet.setFrozenRows(1);
  }
  return sheet;
}

/** Run once: makes the sheets, with the overview. */
function setup() {
  var ss = SpreadsheetApp.getActive();
  installsSheet();
  var o = ss.getSheetByName('Overview') || ss.insertSheet('Overview', 0);
  o.clear();
  var rows = [
    ['Installaties (totaal)', '=COUNTA(Installs!A2:A)'],
    ['Actief vandaag', '=COUNTIF(Installs!C2:C,">"&(NOW()-1))'],
    ['Actief laatste 7 dagen', '=COUNTIF(Installs!C2:C,">"&(NOW()-7))'],
    ['Actief laatste 30 dagen', '=COUNTIF(Installs!C2:C,">"&(NOW()-30))'],
    ['Nieuw laatste 7 dagen', '=COUNTIF(Installs!B2:B,">"&(NOW()-7))'],
    ['Keer geopend (totaal)', '=SUM(Installs!D2:D)'],
    ['Gemiddeld aantal keer geopend per installatie', '=IFERROR(ROUND(B6/B1,1),0)']
  ];
  o.getRange(1, 1, rows.length, 2).setValues(rows);
  o.getRange('A1:A7').setFontWeight('bold');
  o.getRange('D1').setFormula('=IFERROR(QUERY(Installs!A:E,"select E, count(A) where A is not null and E is not null group by E label E \'Versie\', count(A) \'Installaties\'",1),"")');
  o.setColumnWidth(1, 330);
  o.setColumnWidth(2, 90);
  o.setColumnWidth(4, 90);
}
