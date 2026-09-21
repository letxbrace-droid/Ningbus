#!/usr/bin/env python3
"""Contrôles statiques hors Android. Ne remplace ni Gradle ni l'émulateur.

Usage : python3 tests_ui_sandbox.py /chemin/vers/archive-originale.zip
"""
import re
import sys
import unittest
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parent
ARCHIVE = zipfile.ZipFile(sys.argv.pop(1))
PREFIX = ARCHIVE.namelist()[0]
ANDROID = '{http://schemas.android.com/apk/res/android}'
KOTLIN = ROOT / 'app/src/main/kotlin/fr/ningbus/arbitre'


def original(relative):
    return ARCHIVE.read(PREFIX + 'arbitre/' + relative)


class UiSandbox(unittest.TestCase):
    def test_01_protected_sources_identical(self):
        protected = ['moteur/', 'simulateur/']
        files = ['LectureEcran.kt', 'Ocr.kt', 'Arbitrage.kt', 'EcouteNotifications.kt',
                 'Reglages.kt', 'ServiceVeille.kt', 'Repli.kt', 'Haptique.kt', 'AuDemarrage.kt']
        checked = 0
        for name in ARCHIVE.namelist():
            prefix = PREFIX + 'arbitre/'
            if not name.startswith(prefix) or name.endswith('/'):
                continue
            relative = name[len(prefix):]
            if any(relative.startswith(p) for p in protected) or Path(relative).name in files:
                self.assertEqual(ARCHIVE.read(name), (ROOT / relative).read_bytes(), relative)
                checked += 1
        self.assertGreater(checked, 20)

    def test_02_no_changes_outside_arbitre(self):
        for name in ARCHIVE.namelist():
            if name.endswith('/'):
                continue
            relative = name[len(PREFIX):]
            if not relative.startswith('arbitre/'):
                self.assertEqual(ARCHIVE.read(name), (ROOT.parent / relative).read_bytes(), relative)

    def test_03_all_xml_well_formed(self):
        for path in ROOT.rglob('*.xml'):
            ET.parse(path)

    def test_04_original_screen_ids_preserved_once(self):
        relative = 'app/src/main/res/layout/principale.xml'
        before = ET.fromstring(original(relative))
        after = ET.parse(ROOT / relative).getroot()
        old_ids = [e.get(ANDROID + 'id') for e in before.iter() if e.get(ANDROID + 'id')]
        new_ids = [e.get(ANDROID + 'id') for e in after.iter() if e.get(ANDROID + 'id')]
        self.assertEqual(len(new_ids), len(set(new_ids)))
        self.assertTrue(set(old_ids) <= set(new_ids))
        switches = [e for e in after.iter() if e.tag.endswith('MaterialSwitch')]
        original_switches = [e for e in before.iter() if e.tag.endswith('SwitchCompat')]
        self.assertEqual(len(switches), len(original_switches))

    def test_05_navigation_and_accessible_controls(self):
        root = ET.parse(ROOT / 'app/src/main/res/menu/navigation.xml').getroot()
        self.assertEqual([e.get(ANDROID+'title') for e in root], ['Accueil', 'Courses', 'Simulateur', 'Réglages'])
        source = (KOTLIN / 'InterfacePremium.kt').read_text()
        self.assertIn('setDuration(220L)', source)
        self.assertIn('areAnimatorsEnabled()', source)
        self.assertIn('removeCallbacks(miseAJour)', source)
        self.assertIn('WindowInsetsCompat.Type.ime()', source)

    def test_06_overlay_gestures_and_nonfocusable_unchanged(self):
        for filename in ['BoutonFlottant.kt', 'Bulle.kt']:
            source = (KOTLIN / filename).read_text()
            before = original('app/src/main/kotlin/fr/ningbus/arbitre/' + filename).decode()
            self.assertIn('WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE', source)
            marker = '    private fun installerGestes('
            self.assertEqual(source[source.index(marker):], before[before.index(marker):])
        source = (KOTLIN / 'BoutonFlottant.kt').read_text()
        self.assertIn('LectureEcran.analyserMaintenant(contexte)', source)
        self.assertIn('LectureEcran.capturer(contexte)', source)

    def test_07_journal_dedup_unchanged_and_no_historical_recalculation(self):
        source = (KOTLIN / 'Journal.kt').read_text()
        before = original('app/src/main/kotlin/fr/ningbus/arbitre/Journal.kt').decode()
        marker = '    fun vider('
        self.assertEqual(source[source.index(marker):], before[before.index(marker):])
        self.assertIn('verdict.euroKm?.let { put("ek", it) }', source)
        ui = (KOTLIN / 'InterfacePremium.kt').read_text()
        self.assertNotIn('Arbitre.arbitrer', ui)
        self.assertIn('€/km non enregistré', ui)

    def test_08_resource_references_exist(self):
        res = ROOT / 'app/src/main/res'
        known = {kind: set() for kind in ['id', 'layout', 'drawable', 'color', 'string', 'style', 'menu']}
        for path in res.rglob('*.xml'):
            kind = path.parent.name.split('-')[0]
            if kind in known and kind != 'id':
                known[kind].add(path.stem)
            for node in ET.parse(path).iter():
                identity = node.get(ANDROID+'id', '')
                if identity.startswith('@+id/'):
                    known['id'].add(identity.split('/')[1])
                if node.tag in known and node.get('name'):
                    known[node.tag].add(node.get('name').replace('.', '_'))
        for path in KOTLIN.glob('*.kt'):
            for kind, name in re.findall(r'(?<!android\.)R\.(id|layout|drawable|color|string|style|menu)\.(\w+)', path.read_text()):
                self.assertIn(name, known[kind], f'{path.name}: R.{kind}.{name}')
        for path in res.rglob('*.xml'):
            for kind, name in re.findall(r'@(layout|drawable|color|string|menu)/(\w+)', path.read_text()):
                self.assertIn(name, known[kind], f'{path.name}: @{kind}/{name}')


if __name__ == '__main__':
    unittest.main(verbosity=2)
