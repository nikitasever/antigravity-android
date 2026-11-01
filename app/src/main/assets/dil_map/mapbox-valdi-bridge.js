/**
 * Adapt the production openai-android Mapbox document to Valdi's WebView bridge.
 * The adjacent mapbox.html and mapbox.js are copied from platform/map/public.
 */
(function installValdiMapBridge() {
  "use strict";

  const routeSourceId = "dil-map-routes";
  const routeLayerId = "dil-map-route-lines";
  const pendingMessages = [];
  let activeMap;
  let configuration;
  let lastBottomPadding;
  let lastFocusedMarkerId;
  let lastMarkerState;
  let lastNearbyRequestKey = -1;
  let lastViewportRequestKey = -1;
  let retryScheduled = false;
  let searchController;

  function flushMessages() {
    if (!window.Valdi || typeof window.Valdi.postMessage !== "function") {
      if (!retryScheduled && pendingMessages.length > 0) {
        retryScheduled = true;
        window.setTimeout(function retryBridge() {
          retryScheduled = false;
          flushMessages();
        }, 40);
      }
      return;
    }
    while (pendingMessages.length > 0) {
      window.Valdi.postMessage(JSON.stringify(pendingMessages.shift()));
    }
  }

  function send(message) {
    pendingMessages.push(message);
    flushMessages();
  }

  window.AndroidInterface = {
    notifyInitialContentsLoaded() {
      send({ status: "ready", type: "status" });
    },
    notifyMapClick() {
      send({ type: "mapClicked" });
    },
    notifyMarkerSelect(markerId) {
      send({ markerId, type: "markerSelected" });
    },
    notifyViewportChanged(payload) {
      try {
        const viewport = JSON.parse(payload);
        viewport.zoom = activeMap ? activeMap.getZoom() : 12;
        send({ type: "viewport", viewport });
      } catch (_error) {
        send({
          message: "The map viewport could not be read.",
          status: "error",
          type: "status",
        });
      }
    },
  };

  function resolveRouteColor(color) {
    const colors = {
      "--blue-500": "#3b82f6",
      "--green-500": "#22c55e",
      "--orange-500": "#f97316",
      "--purple-500": "#a855f7",
      "--red-500": "#ef4444",
    };
    return colors[color] || color || "#3b82f6";
  }

  function updateRoutes(routes) {
    if (
      !activeMap ||
      typeof activeMap.isStyleLoaded !== "function" ||
      !activeMap.isStyleLoaded()
    ) {
      return;
    }
    const data = {
      features: routes.map(function makeRoute(route) {
        return {
          geometry: { coordinates: route.coordinates, type: "LineString" },
          properties: { color: resolveRouteColor(route.color) },
          type: "Feature",
        };
      }),
      type: "FeatureCollection",
    };
    const source = activeMap.getSource(routeSourceId);
    if (source) {
      source.setData(data);
      return;
    }
    if (routes.length === 0) {
      return;
    }
    activeMap.addSource(routeSourceId, { data, type: "geojson" });
    activeMap.addLayer({
      id: routeLayerId,
      layout: { "line-cap": "round", "line-join": "round" },
      paint: { "line-color": ["get", "color"], "line-width": 4 },
      source: routeSourceId,
      type: "line",
    });
  }

  function updateCamera(configuration) {
    const paddingChanged = configuration.bottomPadding !== lastBottomPadding;
    lastBottomPadding = configuration.bottomPadding;
    const focused = configuration.markers.find(function findMarker(marker) {
      return marker.id === configuration.focusedMarkerId;
    });
    if (
      focused &&
      (configuration.focusedMarkerId !== lastFocusedMarkerId || paddingChanged)
    ) {
      lastFocusedMarkerId = configuration.focusedMarkerId;
      window.updateCamera(
        focused.longitude,
        focused.latitude,
        Math.max(configuration.viewport.zoom, 14),
        configuration.bottomPadding,
        0.35
      );
      return;
    }
    lastFocusedMarkerId = configuration.focusedMarkerId;

    if (
      configuration.fitBoundsRequestKey === lastViewportRequestKey &&
      !paddingChanged
    ) {
      return;
    }
    lastViewportRequestKey = configuration.fitBoundsRequestKey;
    if (configuration.viewport.fitBounds) {
      const locations = configuration.markers.slice();
      for (const route of configuration.routes) {
        for (const coordinates of route.coordinates) {
          locations.push({
            latitude: coordinates[1],
            longitude: coordinates[0],
          });
        }
      }
      if (locations.length > 0) {
        window.fitMarkersToBounds(
          locations,
          true,
          configuration.bottomPadding,
          0.35
        );
      }
    }
  }

  function nearbyBoundingBox(viewport) {
    if (!(viewport.latitudeSpan > 0 && viewport.longitudeSpan > 0)) {
      return undefined;
    }
    const west = Math.max(
      -180,
      viewport.longitude - viewport.longitudeSpan / 2
    );
    const south = Math.max(-90, viewport.latitude - viewport.latitudeSpan / 2);
    const east = Math.min(180, viewport.longitude + viewport.longitudeSpan / 2);
    const north = Math.min(90, viewport.latitude + viewport.latitudeSpan / 2);
    return west < east && south < north
      ? [west, south, east, north]
          .map(function format(value) {
            return value.toFixed(6);
          })
          .join(",")
      : undefined;
  }

  function searchNearby(request) {
    if (!request || request.requestKey === lastNearbyRequestKey) {
      return;
    }
    lastNearbyRequestKey = request.requestKey;
    if (searchController) {
      searchController.abort();
    }
    searchController = new AbortController();
    const controller = searchController;
    send({
      requestKey: request.requestKey,
      status: "loading",
      type: "nearbySearch",
    });

    const url = new URL(
      "https://api.mapbox.com/search/searchbox/v1/category/" +
        encodeURIComponent(request.category)
    );
    url.searchParams.set(
      "proximity",
      request.viewport.longitude + "," + request.viewport.latitude
    );
    const boundingBox = nearbyBoundingBox(request.viewport);
    if (boundingBox) {
      url.searchParams.set("bbox", boundingBox);
    }
    url.searchParams.set("limit", "8");
    url.searchParams.set("access_token", window.mapboxgl.accessToken);

    fetch(url.toString(), { signal: controller.signal })
      .then(function parseResponse(response) {
        if (!response.ok) {
          throw new Error(
            "Nearby search is unavailable (" + response.status + ")."
          );
        }
        return response.json();
      })
      .then(function reportPlaces(payload) {
        if (controller !== searchController) {
          return;
        }
        const places = [];
        for (const feature of payload.features || []) {
          const coordinates = feature.geometry && feature.geometry.coordinates;
          const properties = feature.properties;
          if (
            !coordinates ||
            !properties ||
            typeof properties.name !== "string"
          ) {
            continue;
          }
          const categories = properties.poi_category || [];
          places.push({
            address:
              properties.full_address ||
              properties.place_formatted ||
              properties.address ||
              "",
            category:
              categories.find(function findCategory(value) {
                return typeof value === "string";
              }) || request.category,
            distanceMeters: Number.isFinite(properties.distance)
              ? properties.distance
              : undefined,
            id: properties.mapbox_id || coordinates[0] + "-" + coordinates[1],
            latitude: coordinates[1],
            longitude: coordinates[0],
            name: properties.name,
          });
        }
        send({
          places,
          requestKey: request.requestKey,
          status: "success",
          type: "nearbySearch",
        });
      })
      .catch(function reportSearchFailure(error) {
        if (controller.signal.aborted || controller !== searchController) {
          return;
        }
        send({
          message: error.message || "Nearby search is unavailable.",
          requestKey: request.requestKey,
          status: "error",
          type: "nearbySearch",
        });
      });
  }

  function update() {
    if (!activeMap || !configuration) {
      return;
    }
    const markerState = JSON.stringify(configuration.markers);
    if (markerState !== lastMarkerState) {
      lastMarkerState = markerState;
      window.updateMarkers(configuration.markers);
    }
    window.setMapTheme(configuration.darkTheme, configuration.styleUrl);
    updateRoutes(configuration.routes);
    updateCamera(configuration);
    searchNearby(configuration.nearbySearchRequest);
    flushMessages();
  }

  window.ValdiMapBridge = {
    attach(map) {
      activeMap = map;
      map.on("load", update);
      map.on("styledata", function updateStyledRoutes() {
        if (configuration) {
          updateRoutes(configuration.routes);
        }
      });
      map.on("error", function reportMapError(_event) {
        if (!activeMap.loaded()) {
          send({
            message: "Map tiles are unavailable.",
            status: "error",
            type: "status",
          });
        }
      });
      update();
    },
    configure(value) {
      configuration = value;
      update();
    },
    reportError(message) {
      send({ message, status: "error", type: "status" });
    },
    reportUnavailable(message) {
      send({ message, status: "unavailable", type: "status" });
    },
  };
})();
