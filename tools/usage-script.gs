/**
 * Franxit's anonymous daily count, a Google Apps Script for a Google Sheet.
 *
 * Only aggregated day totals: no device numbers, no user profiles, no product information.
 * Once a day an app that is opened sends {version}: "one more phone today". This script adds 1 to
 * the row of that date and version in the sheet "Daily". The sheet "Overview" shows the totals per
 * day. Nothing in the sheet says anything about one phone or one person.
 *
 * Setup: paste this file in Extensions > Apps Script, run `setup`, then Deploy > Manage deployments
 * > the pencil > Version: New version > Deploy (the web app URL stays the same).
 *
 * Versions 1.0.11 to 1.0.13 of the app sent a number per phone; this script stores none of that. Their
 * deletion requests are still honoured, so the old sheet "Installs" can be emptied; you can delete
 * that tab once the app has been updated.
 */

var TZ = 'Europe/Brussels';
var DAILY = 'Daily';

function doGet() {
  return ContentService.createTextOutput('Franxit counter');
}

function doPost(e) {
  var lock = LockService.getScriptLock();
  try {
    var d = JSON.parse(e.postData.contents);

    // An older app version sends a number per phone: nothing is stored, only a deletion is carried out.
    if (d.id) {
      if (d.delete === true) {
        lock.waitLock(10000);
        deleteOldRow(String(d.id));
      }
      return ok();
    }

    var version = String(d.version || '');
    if (!/^[0-9]+(\.[0-9]+){0,3}$/.test(version) || version.length > 20) return ok();
    lock.waitLock(10000);
    countToday(version);
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

/** Adds 1 to today's row for this version (today's rows are at the bottom). */
function countToday(version) {
  var sheet = dailySheet();
  var day = Utilities.formatDate(new Date(), TZ, 'yyyy-MM-dd');
  var last = sheet.getLastRow();
  if (last >= 2) {
    var from = Math.max(2, last - 60);
    var rows = sheet.getRange(from, 1, last - from + 1, 3).getValues();
    for (var i = rows.length - 1; i >= 0; i--) {
      if (String(rows[i][0]) === day && String(rows[i][1]) === version) {
        sheet.getRange(from + i, 3).setValue(Number(rows[i][2]) + 1);
        return;
      }
    }
  }
  sheet.appendRow([day, version, 1]);
}

function dailySheet() {
  var ss = SpreadsheetApp.getActive();
  var sheet = ss.getSheetByName(DAILY) || ss.insertSheet(DAILY);
  if (sheet.getLastRow() === 0) {
    sheet.getRange('A:B').setNumberFormat('@');   // dates and versions stay text
    sheet.appendRow(['Date', 'Version', 'Phones']);
    sheet.getRange('A1:C1').setFontWeight('bold');
    sheet.setFrozenRows(1);
  }
  return sheet;
}

/** Removes a number an older app version made (only when it asks for it). */
function deleteOldRow(id) {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id)) return;
  var sheet = SpreadsheetApp.getActive().getSheetByName('Installs');
  if (!sheet || sheet.getLastRow() < 2) return;
  var ids = sheet.getRange(2, 1, sheet.getLastRow() - 1, 1).getValues().map(function (r) { return r[0]; });
  var row = ids.indexOf(id);
  if (row !== -1) sheet.deleteRow(row + 2);
}

/** Old clean-up of the "Installs" tab (no longer used; kept so an old trigger doesn't fail). */
function purgeOld() {}

/** Run once (and again after changing this file): makes the sheets and the overview. */
function setup() {
  var ss = SpreadsheetApp.getActive();
  dailySheet();
  var o = ss.getSheetByName('Overview') || ss.insertSheet('Overview', 0);
  o.clear();
  // Per day: how many phones were used (the versions added up).
  o.getRange('A1').setFormula('=IFERROR(QUERY(Daily!A:C,"select A, sum(C) where A is not null group by A order by A desc label A \'Datum\', sum(C) \'Actieve gebruikers\'",1),"")');
  // Per version, all days together.
  o.getRange('D1').setFormula('=IFERROR(QUERY(Daily!A:C,"select B, sum(C) where B is not null group by B order by B desc label B \'Versie\', sum(C) \'Meldingen (alle dagen)\'",1),"")');
  o.getRange('G1:H1').setValues([['Samenvatting', '']]).setFontWeight('bold');
  o.getRange('G2:H4').setValues([
    ['Laatste dag met data', '=IFERROR(INDEX(A2:A,1),"")'],
    ['Gemiddeld laatste 7 dagen', '=IFERROR(ROUND(AVERAGE(B2:B8),1),0)'],
    ['Hoogste dag', '=IFERROR(MAX(B2:B),0)']
  ]);
  o.getRange('A1:B1').setFontWeight('bold');
  o.getRange('D1:E1').setFontWeight('bold');
  o.setColumnWidth(1, 110);
  o.setColumnWidth(2, 140);
  o.setColumnWidth(4, 90);
  o.setColumnWidth(5, 170);
  o.setColumnWidth(7, 190);

  // The old daily clean-up of the "Installs" tab isn't needed any more.
  ScriptApp.getProjectTriggers().forEach(function (t) {
    if (t.getHandlerFunction() === 'purgeOld') ScriptApp.deleteTrigger(t);
  });
}
