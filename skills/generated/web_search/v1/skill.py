#!/usr/bin/env python3
import json
import sys
import requests
from bs4 import BeautifulSoup

def perform_web_search(query):
    search_url = f'https://www.google.com/search?q={query}'
    headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3'}
    response = requests.get(search_url, headers=headers)
    if response.status_code == 200:
        soup = BeautifulSoup(response.text, 'html.parser')
        result = soup.find('div', class_='BNeawe UPmit AP7Wnd')
        if result:
            return result.text
        else:
            return 'No results found.'
    else:
        return 'Error fetching search results.'

if __name__ == "__main__":
    try:
        params = json.loads(sys.stdin.read())
        query = params.get('query')
        if not query:
            raise ValueError('Missing required parameter: query')
        print(json.dumps({"type": "progress", "message": "Performing web search..."}))
        result = perform_web_search(query)
        print(json.dumps({"type": "result", "status": "success", "output": {"result": result}}))
    except Exception as e:
        print(json.dumps({"type": "result", "status": "error", "output": {"error": str(e)}}))
