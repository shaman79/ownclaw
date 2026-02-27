import requests, re

r = requests.get('https://www.hospoda-orechovska.cz/menu-na-tento-tyden/', 
                 headers={'User-Agent': 'Mozilla/5.0'}, timeout=15)
r.encoding = r.apparent_encoding

# Find ÚTERÝ section structure
idx = r.text.find('ÚTERÝ')
if idx >= 0:
    print(f'ÚTERÝ found at index {idx}')
    start = max(0, idx-500)
    print(r.text[start:idx+500])
else:
    print('ÚTERÝ not in raw HTML')

print('\n--- Tab/panel CSS classes ---')
panels = re.findall(r'class="[^"]*(?:tab|panel|collapse|accordion|hidden|active)[^"]*"', r.text, re.I)
print(f'Found {len(panels)} tab/panel elements:')
for p in panels[:15]:
    print(f'  {p}')
