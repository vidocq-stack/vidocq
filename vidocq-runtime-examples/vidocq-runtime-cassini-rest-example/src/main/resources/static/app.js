/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
const API = '/api/todos';

const list = document.getElementById('todo-list');
const form = document.getElementById('add-form');
const input = document.getElementById('add-input');

async function loadTodos() {
    const res = await fetch(API, { headers: { Accept: 'application/json' } });
    const todos = res.ok ? await res.json() : [];
    render(todos);
}

function render(todos) {
    list.innerHTML = '';
    if (!todos.length) {
        list.innerHTML = '<li class="empty">Aucune tâche pour le moment.</li>';
        return;
    }
    todos.sort((a, b) => a.id - b.id);
    for (const todo of todos) {
        const li = document.createElement('li');
        if (todo.done) li.classList.add('done');

        const cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.checked = todo.done;
        cb.addEventListener('change', () => toggle(todo, cb.checked));

        const title = document.createElement('span');
        title.className = 'title';
        title.textContent = todo.title;

        const del = document.createElement('button');
        del.className = 'delete';
        del.textContent = '✕';
        del.title = 'Supprimer';
        del.addEventListener('click', () => remove(todo));

        li.append(cb, title, del);
        list.append(li);
    }
}

async function add(title) {
    await fetch(API, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id: 0, title, done: false }),
    });
    await loadTodos();
}

async function toggle(todo, done) {
    await fetch(`${API}/${todo.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id: todo.id, title: todo.title, done }),
    });
    await loadTodos();
}

async function remove(todo) {
    await fetch(`${API}/${todo.id}`, { method: 'DELETE' });
    await loadTodos();
}

form.addEventListener('submit', (e) => {
    e.preventDefault();
    const title = input.value.trim();
    if (!title) return;
    input.value = '';
    add(title);
});

loadTodos();
