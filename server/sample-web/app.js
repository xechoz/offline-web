const statusNode = document.getElementById('status')
const cardsNode = document.getElementById('cards')

function render(items) {
  cardsNode.innerHTML = ''
  for (const item of items) {
    const card = document.createElement('article')
    card.className = 'card'

    const title = document.createElement('h3')
    title.textContent = item.name

    const description = document.createElement('p')
    description.textContent = item.description

    const price = document.createElement('span')
    price.className = 'price'
    price.textContent = `¥ ${item.price}`

    card.append(title, description, price)
    cardsNode.append(card)
  }
}

fetch('data.json')
  .then((response) => {
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    return response.json()
  })
  .then((data) => {
    document.getElementById('build').textContent = `build ${data.build}`
    statusNode.textContent = `已从本地加载 ${data.items.length} 件商品 · ${new Date().toLocaleTimeString()}`
    render(data.items)
  })
  .catch((error) => {
    statusNode.textContent = `数据加载失败：${error.message}`
  })
