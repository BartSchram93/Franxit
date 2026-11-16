/**
 * Franxit's anonymous usage counter, a Google Apps Script for a Google Sheet.
 *
 * Setup (once): create a Sheet, then Extensions > Apps Script, paste this file, run `setup`, and
 * Deploy > New deployment > Web app (Execute as: Me, Who has access: Anyone). Put the web app URL
 * in Usage.kt (ENDPOINT). After changing this file: Deploy > Manage deployments > the pencil >
 * Version: New version > Deploy (the URL stays the same), and run `setup` again.
 *
 * Only for people who said yes in the app. The app sends {id, version, opens, bol_clicks}: a random
 * number made on the phone, the app version, how many times the app was opened and how often "Buy
 * at Bol.com" was tapped. One row per installation in the sheet "Installs"; the sheet "Overview"
 * sums it up. {id, delete: true} (switching off or "Delete my number" in the app) deletes that row.
 * Rows not seen for a year are deleted every day (see purgeOld). Only the owner of the Sheet sees any of it.
 */

var INSTALLS = 'Installs';
var HEADER = ['Install', 'First seen', 'Last seen', 'Opens', 'Version', 'Bol.com taps'];
var KEEP_DAYS = 365;

function doGet() {
  return ContentService.createTextOutput('Franxit counter');
}

function doPost(e) {
  var lock = LockService.getScriptLock();
  try {
    var d = JSON.parse(e.postData.contents);
    var id = String(d.id || '');
    // Only what the app sends: a random id of the form 8-4-4-4-12 hex digits.
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id)) return ok();

    lock.waitLock(10000);
    var sheet = installsSheet();
    var last = sheet.getLastRow();
    var ids = last > 1 ? sheet.getRange(2, 1, last - 1, 1).getValues().map(function (r) { return r[0]; }) : [];
    var row = ids.indexOf(id);

    if (d.delete === true) {
      if (row !== -1) sheet.deleteRow(row + 2);
      return ok();
    }

    var opens = Math.max(0, Math.min(Number(d.opens) || 0, 1000000));
    var bol = Math.max(0, Math.min(Number(d.bol_clicks) || 0, 1000000));
    var version = String(d.version || '').slice(0, 20);
    var now = new Date();
    if (row === -1) {
      sheet.appendRow([id, now, now, opens, version, bol]);
    } else {
      sheet.getRange(row + 2, 3, 1, 4).setValues([[now, opens, version, bol]]);
    }
  } catch (err) {
    console.error(err);
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
    sheet.getRange('A1:F1').setFontWeight('bold');
    sheet.setFrozenRows(1);
  } else if (sheet.getRange('F1').getValue() === '') {
    sheet.getRange('F1').setValue(HEADER[5]).setFontWeight('bold');
  }
  return sheet;
}

/** Deletes the installs not seen for a year (run daily by the trigger that `setup` makes). */
function purgeOld() {
  var sheet = installsSheet();
  var last = sheet.getLastRow();
  if (last < 2) return;
  var seen = sheet.getRange(2, 3, last - 1, 1).getValues();
  var limit = new Date().getTime() - KEEP_DAYS * 24 * 3600 * 1000;
  for (var i = seen.length - 1; i >= 0; i--) {
    var t = seen[i][0] instanceof Date ? seen[i][0].getTime() : 0;
    if (t && t < limit) sheet.deleteRow(i + 2);
  }
}

/** Run once (and again after changing this file): makes the sheets, the overview and the daily clean-up. */
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
    ['Gemiddeld aantal keer geopend per installatie', '=IFERROR(ROUND(B6/B1,1),0)'],
    ['Taps op "Buy at Bol.com" (totaal)', '=SUM(Installs!F2:F)']
  ];
  o.getRange(1, 1, rows.length, 2).setValues(rows);
  o.getRange('A1:A8').setFontWeight('bold');
  o.getRange('D1').setFormula('=IFERROR(QUERY(Installs!A:E,"select E, count(A) where A is not null and E is not null group by E label E \'Versie\', count(A) \'Installaties\'",1),"")');
  o.setColumnWidth(1, 330);
  o.setColumnWidth(2, 90);
  o.setColumnWidth(4, 90);

  // The daily clean-up (once: older ones are replaced).
  ScriptApp.getProjectTriggers().forEach(function (t) {
    if (t.getHandlerFunction() === 'purgeOld') ScriptApp.deleteTrigger(t);
  });
  ScriptApp.newTrigger('purgeOld').timeBased().everyDays(1).atHour(4).create();
}
