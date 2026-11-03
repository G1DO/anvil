(function () {
  "use strict";

  var PAGE_SIZE = 100;

  var form = document.getElementById("inspect-form");
  var tenantInput = document.getElementById("tenant");
  var jobInput = document.getElementById("job-id");
  var inspectButton = document.getElementById("inspect");
  var statusLine = document.getElementById("status");
  var result = document.getElementById("result");
  var notFound = document.getElementById("not-found");
  var tbody = document.querySelector("#audit-events tbody");
  var chainNote = document.getElementById("chain-note");
  var loadMoreButton = document.getElementById("load-more");

  // The currently rendered chain, so "Load more" uses the same tenant and cursor.
  var current = null;

  function setText(id, value) {
    document.getElementById(id).textContent = value == null ? "" : String(value);
  }

  function setStatus(message) {
    statusLine.textContent = message;
  }

  function reset() {
    current = null;
    result.hidden = true;
    notFound.hidden = true;
    chainNote.hidden = true;
    chainNote.textContent = "";
    loadMoreButton.hidden = true;
    clear(tbody);
    setStatus("");
  }

  /**
   * GET a JSON resource with the tenant in the API's header. The browser never
   * trusts a tenant from the URL or body, and the API ignores those too.
   */
  function getJson(path, tenant, done) {
    var xhr = new XMLHttpRequest();
    var finished = false;

    function complete(status, body) {
      if (finished) {
        return;
      }
      finished = true;
      done(status, body);
    }

    xhr.open("GET", path, true);
    xhr.onreadystatechange = function () {
      if (xhr.readyState !== 4) {
        return;
      }
      var body = null;
      if (xhr.responseText) {
        try {
          body = JSON.parse(xhr.responseText);
        } catch (ignored) {
          body = null;
        }
      }
      complete(xhr.status, body);
    };
    try {
      xhr.setRequestHeader("X-Tenant-Id", tenant);
      xhr.send();
    } catch (ignored) {
      // An invalid header value or a blocked request is not a job verdict.
      complete(0, null);
    }
  }

  function inspect() {
    var tenant = tenantInput.value.trim();
    var jobId = jobInput.value.trim();
    reset();

    if (!tenant || !jobId) {
      setStatus("Enter a tenant and a job ID.");
      return;
    }

    inspectButton.disabled = true;
    form.classList.add("busy");
    setStatus("Inspecting...");

    var responses = {};
    var remaining = 3;

    function finished(name) {
      return function (status, body) {
        responses[name] = { status: status, body: body };
        remaining--;
        if (remaining === 0) {
          inspectButton.disabled = false;
          form.classList.remove("busy");
          render(tenant, jobId, responses);
        }
      };
    }

    getJson("/api/jobs/" + encodeURIComponent(jobId), tenant, finished("job"));
    getJson("/audit/events?job_id=" + encodeURIComponent(jobId) + "&limit=" + PAGE_SIZE,
        tenant, finished("events"));
    getJson("/audit/replay?job_id=" + encodeURIComponent(jobId), tenant, finished("replay"));
  }

  function render(tenant, jobId, responses) {
    var job = responses.job;
    var events = responses.events;
    var replay = responses.replay;

    setStatus("");

    if (job.status === 401 || events.status === 401 || replay.status === 401) {
      notFound.hidden = true;
      result.hidden = true;
      setStatus("Tenant not found.");
      return;
    }

    // A missing job and another tenant's job are the same response at every
    // step, so the console shows the same generic answer after all three finish.
    if (job.status === 404) {
      result.hidden = true;
      notFound.hidden = false;
      return;
    }

    if (job.status !== 200 || events.status !== 200 || replay.status !== 200) {
      notFound.hidden = true;
      result.hidden = true;
      setStatus("The job could not be loaded.");
      return;
    }

    notFound.hidden = true;
    result.hidden = false;

    setText("job-state", job.body && job.body.state);
    setText("job-id-value", job.body && job.body.id);
    setText("tenant-value", job.body && job.body.tenant_id);
    setText("replay-match", replay.body ? String(replay.body.match) : "");
    setText("replay-input-sha", replay.body && replay.body.input_sha);
    setText("replay-output-sha", replay.body && replay.body.output_sha);

    var chain = events.body instanceof Array ? events.body : [];
    current = { tenant: tenant, jobId: jobId, events: chain };
    renderEvents(chain, false);
  }

  function renderEvents(events, append) {
    if (!append) {
      clear(tbody);
    }
    for (var i = 0; i < events.length; i++) {
      tbody.appendChild(row(events[i]));
    }

    var total = current ? current.events.length : events.length;
    chainNote.hidden = total === 0;
    chainNote.textContent = total === 0 ? "" : "Showing " + total + " event" + (total === 1 ? "" : "s") + ".";
    loadMoreButton.hidden = !current || events.length !== PAGE_SIZE;
  }

  function row(event) {
    var tr = document.createElement("tr");
    appendCell(tr, event.seq);
    appendCell(tr, event.type);
    appendCell(tr, event.event_hash, "mono hash");
    appendCell(tr, event.prev_hash, "mono hash");
    var payload = event.payload == null ? "{}" : JSON.stringify(event.payload);
    appendCell(tr, payload, "mono payload");
    return tr;
  }

  function appendCell(tr, value, className) {
    var td = document.createElement("td");
    td.textContent = value == null ? "" : String(value);
    if (className) {
      td.className = className;
    }
    tr.appendChild(td);
  }

  function clear(node) {
    while (node.firstChild) {
      node.removeChild(node.firstChild);
    }
  }

  function loadMore() {
    if (!current || current.events.length === 0) {
      return;
    }
    var cursor = current.events[current.events.length - 1].id;
    loadMoreButton.disabled = true;
    getJson("/audit/events?job_id=" + encodeURIComponent(current.jobId)
        + "&limit=" + PAGE_SIZE + "&cursor=" + encodeURIComponent(cursor),
    current.tenant, function (status, body) {
      loadMoreButton.disabled = false;
      if (status === 404) {
        result.hidden = true;
        notFound.hidden = false;
        return;
      }
      if (status !== 200 || !(body instanceof Array)) {
        setStatus("The rest of the chain could not be loaded.");
        return;
      }
      current.events = current.events.concat(body);
      renderEvents(body, true);
    });
  }

  form.addEventListener("submit", function (event) {
    event.preventDefault();
    inspect();
  });
  loadMoreButton.addEventListener("click", loadMore);
})();
