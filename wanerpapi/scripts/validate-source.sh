#!/usr/bin/env bash
set -euo pipefail

PLUGIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

python3 - "$PLUGIN_DIR" <<'PY'
from pathlib import Path
import re, sys, xml.etree.ElementTree as ET
root = Path(sys.argv[1])
component = root / 'ofbiz-component.xml'
main_xml = root / 'servicedef/services.xml'
admin_xml = root / 'servicedef/services_admin.xml'
seed_xml = root / 'data/neademodata.xml'
java = root / 'src/main/java/org/apache/ofbiz/wanerpapi/api/WanErpApiServices.java'
for p in [component, main_xml, admin_xml, seed_xml]:
    ET.parse(p)

main = ET.parse(main_xml).getroot().findall('service')
admin = ET.parse(admin_xml).getroot().findall('service')
assert len(main) == 18, f'Expected 18 operational services, got {len(main)}'
assert len(admin) == 14, f'Expected 14 admin services, got {len(admin)}'

text = java.read_text()
for svc in main + admin:
    invoke = svc.attrib['invoke']
    assert re.search(r'public\s+static\s+Map<String,\s*Object>\s+' + re.escape(invoke) + r'\s*\(', text), \
        f'Missing Java invoke method: {invoke}'
assert '"effectiveDate"' not in text, 'Do not pass effectiveDate to createInventoryItemDetail in release24.09'
assert 'wanerpApiRegisterEndUserCustomer' in main_xml.read_text()
assert 'wanerpAdminRegisterEndUserCustomer' in admin_xml.read_text()
assert 'registerEndUserCustomer' in text
assert 'createPartyEmailAddress' in text and 'createPartyTelecomNumber' in text and 'createPartyPostalAddress' in text
assert (root/'docs/ENDUSER-CUSTOMER-REGISTRATION.md').exists()

component_text = component.read_text()
assert 'reader-name="ext-demo"' in component_text and 'data/neademodata.xml' in component_text

seed = ET.parse(seed_xml).getroot()
# Basic deterministic dataset assertions.
assert len(seed.findall('ProductStore')) == 2, 'Expected 2 NEA ProductStores'
assert len(seed.findall('ProdCatalog')) == 2, 'Expected 2 NEA Product Catalogs'
assert len(seed.findall('Product')) == 10, 'Expected 10 NEA products/services'
assert len(seed.findall('Employment')) == 6, 'Expected 6 fictional NEA demo employees'
assert len(seed.findall('GlAccount')) == 15, 'Expected 15 starter NEA GL accounts'

# Guard against accidental resurrection of the superseded Kumul Pride dataset.
for p in [seed_xml, java, root/'README.md', root/'docs/API.md', root/'docs/API-CURLS.md', root/'docs/SETUP-CURLS.md']:
    if p.exists():
        t = p.read_text(errors='ignore')
        for forbidden in ['KUMUL_PRIDE', 'KP_RETAIL', 'KP-TEST', 'KP_PERMITS', 'KP_POS_01', 'Kumul Pride']:
            assert forbidden not in t, f'Found superseded demo identifier {forbidden} in {p}'

print('XML_PARSE=PASS')
print('OPERATIONAL_SERVICES=18')
print('ADMIN_SERVICES=14')
print('XML_TO_JAVA_INVOKES=PASS')
print('INVENTORY_EFFECTIVE_DATE_GUARD=PASS')
print('ENDUSER_CUSTOMER_REGISTRATION=PASS')
print('NEA_SEED_XML=PASS')
print('EXT_DEMO_REGISTRATION=PASS')
print('NEA_STORES=2')
print('NEA_CATALOGS=2')
print('NEA_PRODUCTS=10')
print('NEA_EMPLOYEES=6')
print('NEA_GL_ACCOUNTS=15')
print('NO_KUMUL_PRIDE_SEED_IDS=PASS')
PY

bash -n "$PLUGIN_DIR/scripts/test-local.sh"
bash -n "$PLUGIN_DIR/scripts/test-enduser-registration.sh"
bash -n "$PLUGIN_DIR/scripts/load-nea-demo-data.sh"
bash -n "$PLUGIN_DIR/scripts/reset-cloudcode-and-seed-nea.sh"
echo 'SHELL_SYNTAX=PASS'
echo 'SOURCE_VALIDATION=PASS'
