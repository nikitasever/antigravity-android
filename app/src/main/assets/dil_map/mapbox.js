const queryString = window.location.search;
const params = new URLSearchParams(queryString);
const initialDarkTheme = params.get("darkTheme") === "true";
const initialMapStyleUrl =
  params.get("mapStyleUrl") || "mapbox://styles/mapbox/streets-v11";

const configuredMapboxAccessToken =
  window.__OAI_MAPBOX_PUBLIC_ACCESS_TOKEN__ || mapboxgl.accessToken;
if (
  typeof configuredMapboxAccessToken !== "string" ||
  configuredMapboxAccessToken.trim().length === 0
) {
  window.ValdiMapBridge.reportUnavailable(
    "Mapbox access is not configured for this device."
  );
}
mapboxgl.accessToken = configuredMapboxAccessToken || "";

function applyDocumentTheme(darkTheme) {
  document.documentElement.dataset.theme = darkTheme ? "dark" : "light";
}

applyDocumentTheme(initialDarkTheme);

const defaultPadding = 25;
const initialBottomPadding = parseFloat(params.get("bottomPadding")) || 0;
const initialFocusVerticalFraction = parseFloat(
  params.get("focusVerticalFraction")
);
const basePadding = {
  top: defaultPadding,
  bottom: defaultPadding + initialBottomPadding,
  left: defaultPadding,
  right: defaultPadding,
};
const mapConfig = {
  container: "map",
  style: initialMapStyleUrl,
  interactive: params.get("interactive") === "true",
  padding: basePadding,
};

// If the location provided, set it as the map center
const latitude = params.get("latitude");
const longitude = params.get("longitude");
if (latitude && longitude) {
  mapConfig.center = [longitude, latitude];
  mapConfig.zoom = parseFloat(params.get("zoom"));
}

const map = new mapboxgl.Map(mapConfig);
let userAvatarUrl = params.get("userAvatarUrl");
const defaultUserAccentColor = "#1A73E8";
const geolocationPermissionDeniedCode = 1;
const initialFastLocationOptions = {
  enableHighAccuracy: false,
  maximumAge: 30000,
  timeout: 2500,
};
const trackedLocationOptions = {
  enableHighAccuracy: true,
  maximumAge: 10000,
  timeout: 10000,
};
const locationSourcePriority = {
  fastInitial: 1,
  tracked: 2,
};
const staleLocationToleranceMs = 1500;
const minimumMeaningfulMoveMeters = 1.5;
const minimumMeaningfulHeadingDelta = 5;

function normalizeHexColor(color) {
  if (typeof color !== "string") {
    return defaultUserAccentColor;
  }
  const trimmed = color.trim();
  const match = /^#?([0-9a-fA-F]{6})$/.exec(trimmed);
  if (!match) {
    return defaultUserAccentColor;
  }
  return `#${match[1].toUpperCase()}`;
}

function hexToRgba(color, alpha) {
  const hex = normalizeHexColor(color);
  const red = parseInt(hex.slice(1, 3), 16);
  const green = parseInt(hex.slice(3, 5), 16);
  const blue = parseInt(hex.slice(5, 7), 16);
  return `rgba(${red}, ${green}, ${blue}, ${alpha})`;
}

let userAccentColor = normalizeHexColor(params.get("userAccentColor"));

function applyAccentVariables(target, color) {
  target.style.setProperty("--user-accent-color", color);
  target.style.setProperty("--user-accent-shadow", hexToRgba(color, 0.24));
  target.style.setProperty("--user-accent-heading", hexToRgba(color, 0.65));
}

function setUserAccentColor(color) {
  userAccentColor = normalizeHexColor(color);
  applyAccentVariables(document.documentElement, userAccentColor);
  document.querySelectorAll(".user-location-overlay").forEach((element) => {
    applyAccentVariables(element, userAccentColor);
  });
}

function normalizeUserAvatarUrl(url) {
  if (typeof url !== "string") {
    return null;
  }
  const trimmed = url.trim();
  return trimmed ? trimmed : null;
}

setUserAccentColor(userAccentColor);
const markerHitPadding = 12;
let currentMarkers = [];
let lastMoveWasUser = false;
let mapHasSettled = false;
let currentMapStyleUrl = initialMapStyleUrl;

// oxlint-disable-next-line no-unused-vars -- Called through window by the Android Mapbox bridge.
function setMapTheme(darkTheme, styleUrl) {
  applyDocumentTheme(darkTheme);
  if (
    typeof styleUrl !== "string" ||
    !styleUrl ||
    styleUrl === currentMapStyleUrl
  ) {
    return;
  }
  currentMapStyleUrl = styleUrl;
  map.setStyle(styleUrl);
}

function notifyViewportChanged(isUserInitiated) {
  if (
    !window.AndroidInterface ||
    !window.AndroidInterface.notifyViewportChanged
  ) {
    return;
  }
  const bounds = map.getBounds();
  const center = map.getCenter();
  const northEast = bounds.getNorthEast();
  const southWest = bounds.getSouthWest();
  const payload = {
    latitude: center.lat,
    longitude: center.lng,
    latitudeSpan: Math.abs(northEast.lat - southWest.lat),
    longitudeSpan: Math.abs(northEast.lng - southWest.lng),
    isUserInitiated: Boolean(isUserInitiated),
  };
  window.AndroidInterface.notifyViewportChanged(JSON.stringify(payload));
}

const starIcon = `
  <svg class="star-icon" viewBox="0 0 24 24" aria-hidden="true">
    <path
      d="M10.258 1.555c.765-1.358 2.719-1.358 3.484 0l2.555 4.531 5.099 1.029c1.527.308 2.131 2.167 1.077 3.314l-3.521 3.83.597 5.168c.179 1.547-1.402 2.696-2.819 2.048L12 19.31l-4.73 2.165c-1.417.648-2.998-.501-2.819-2.048l.597-5.168-3.521-3.83C0.473 9.282 1.077 7.423 2.604 7.115l5.099-1.029z"
      fill="currentColor"
    />
  </svg>
`;

function getPadding(bottomPaddingOverride) {
  const bottomPadding =
    typeof bottomPaddingOverride === "number"
      ? bottomPaddingOverride
      : initialBottomPadding;
  return {
    top: defaultPadding,
    bottom: defaultPadding + bottomPadding,
    left: defaultPadding,
    right: defaultPadding,
  };
}

function normalizeFocusVerticalFraction(focusVerticalFraction) {
  if (!Number.isFinite(focusVerticalFraction)) {
    return 0.5;
  }
  return Math.min(1, Math.max(0, focusVerticalFraction));
}

function getEffectiveBottomPadding(bottomPaddingOverride) {
  const rawBottomPadding =
    typeof bottomPaddingOverride === "number"
      ? bottomPaddingOverride
      : initialBottomPadding;
  const mapHeight = map.getContainer()?.clientHeight || window.innerHeight || 0;
  const maxBottomPadding = Math.max(0, mapHeight - defaultPadding * 2);
  return Math.min(Math.max(0, rawBottomPadding), maxBottomPadding);
}

function getOffset(bottomPaddingOverride, focusVerticalFractionOverride) {
  const bottomPadding = getEffectiveBottomPadding(bottomPaddingOverride);
  const focusVerticalFraction = normalizeFocusVerticalFraction(
    typeof focusVerticalFractionOverride === "number"
      ? focusVerticalFractionOverride
      : initialFocusVerticalFraction
  );
  const mapHeight = map.getContainer()?.clientHeight || window.innerHeight || 0;
  const paddedTop = defaultPadding;
  const paddedBottom = defaultPadding + bottomPadding;
  const paddedHeight = Math.max(0, mapHeight - paddedTop - paddedBottom);
  const paddedCenterY = paddedTop + paddedHeight / 2;
  const targetY = paddedTop + paddedHeight * focusVerticalFraction;
  return [0, targetY - paddedCenterY];
}

// oxlint-disable-next-line no-unused-vars -- Called through window by the Android Mapbox bridge.
function updateCamera(
  longitude,
  latitude,
  zoom,
  bottomPaddingOverride,
  focusVerticalFractionOverride
) {
  const bottomPadding = getEffectiveBottomPadding(bottomPaddingOverride);
  const cameraOptions = {
    center: [longitude, latitude],
    offset: getOffset(bottomPadding, focusVerticalFractionOverride),
    padding: getPadding(bottomPadding),
  };
  if (typeof zoom === "number") {
    cameraOptions.zoom = zoom;
  }
  map.flyTo(cameraOptions);
}

function fitMarkersToBounds(
  markers,
  animate,
  bottomPaddingOverride,
  focusVerticalFractionOverride
) {
  if (!Array.isArray(markers) || markers.length === 0) {
    return;
  }
  const bounds = new mapboxgl.LngLatBounds();
  markers.forEach((marker) =>
    bounds.extend([marker.longitude, marker.latitude])
  );
  map.fitBounds(bounds, {
    animate: Boolean(animate),
    padding: getPadding(getEffectiveBottomPadding(bottomPaddingOverride)),
    offset: getOffset(bottomPaddingOverride, focusVerticalFractionOverride),
  });
}

let businessMarkers = [];
let geolocateControl = null;
let geolocateLoadHandler = null;
let geolocateEventHandler = null;
let geolocateErrorHandler = null;
let userOverlayMarker = null;
let lastMarkerIds = new Set();
const markerSelectionTransitionDurationMs = Number.parseFloat(
  getComputedStyle(document.documentElement).getPropertyValue(
    "--marker-selection-transition-duration"
  )
);
// Markers are recreated on every update, so retain their prior state and in-flight transition timing.
let lastMarkerStates = new Map();
const markerSelectionTransitionStartTimes = new Map();
let initialFastLocationRequestInFlight = false;
let lastAppliedLocationUpdate = null;
let hasReceivedTrackedLocation = false;

function updateMarkers(markers) {
  businessMarkers.forEach((marker) => marker.remove());
  businessMarkers = [];

  currentMarkers = [];
  const nextMarkerIds = new Set(markers.map((marker) => marker.id));
  const updateTimestamp = performance.now();
  markers.forEach((marker) => {
    const lastMarkerState = lastMarkerStates.get(marker.id);
    const markerElement = document.createElement("div");
    markerElement.className = "custom-marker";
    markerElement.classList.toggle("selected", marker.selected);
    markerElement.classList.toggle("unselected", !marker.selected);
    markerElement.classList.toggle("minimized", marker.minimized);
    if (
      lastMarkerState?.minimized &&
      !lastMarkerState.selected &&
      marker.minimized &&
      marker.selected
    ) {
      markerSelectionTransitionStartTimes.set(marker.id, updateTimestamp);
    }
    const transitionStartTime = markerSelectionTransitionStartTimes.get(
      marker.id
    );
    const transitionElapsedMs =
      transitionStartTime === undefined
        ? null
        : updateTimestamp - transitionStartTime;
    if (
      marker.minimized &&
      marker.selected &&
      transitionElapsedMs !== null &&
      transitionElapsedMs < markerSelectionTransitionDurationMs
    ) {
      markerElement.classList.add("marker-expand-from-minimized");
      markerElement.style.setProperty(
        "--marker-selection-transition-delay",
        `${-transitionElapsedMs}ms`
      );
    } else {
      markerSelectionTransitionStartTimes.delete(marker.id);
    }
    if (!lastMarkerIds.has(marker.id)) {
      markerElement.classList.add("marker-fade-in");
    }
    markerElement.dataset.id = marker.id;
    const showMarkerDetails = !marker.minimized || marker.selected;
    const hasRating =
      typeof marker.rating === "number" && Number.isFinite(marker.rating);
    const pillElement = document.createElement("div");
    pillElement.className = "marker-pill";
    pillElement.innerHTML = showMarkerDetails
      ? `<span class="marker-details">${hasRating ? `${starIcon} ${marker.rating.toFixed(1)}` : "●"}</span>`
      : "";
    markerElement.appendChild(pillElement);

    if (marker.label && showMarkerDetails) {
      const labelElement = document.createElement("div");
      labelElement.className = "marker-label";
      labelElement.textContent = marker.label;
      markerElement.appendChild(labelElement);
    }

    const mapMarker = new mapboxgl.Marker(markerElement)
      .setLngLat([marker.longitude, marker.latitude])
      .addTo(map);

    businessMarkers.push(mapMarker);
    currentMarkers.push({
      id: marker.id,
      selected: marker.selected,
      element: markerElement,
    });
  });

  lastMarkerIds = nextMarkerIds;
  for (const markerId of markerSelectionTransitionStartTimes.keys()) {
    if (!nextMarkerIds.has(markerId)) {
      markerSelectionTransitionStartTimes.delete(markerId);
    }
  }
  lastMarkerStates = new Map(
    markers.map((marker) => [
      marker.id,
      {
        minimized: marker.minimized,
        selected: marker.selected,
      },
    ])
  );
}

function createUserOverlayElement() {
  const wrapper = document.createElement("div");
  wrapper.className = "user-location-overlay";
  wrapper.style.setProperty("--user-accent-color", userAccentColor);
  wrapper.style.setProperty(
    "--user-accent-shadow",
    hexToRgba(userAccentColor, 0.24)
  );
  wrapper.style.setProperty(
    "--user-accent-heading",
    hexToRgba(userAccentColor, 0.65)
  );

  const headingElement = document.createElement("div");
  headingElement.className = "user-location-heading";
  wrapper.appendChild(headingElement);

  if (userAvatarUrl) {
    const avatarElement = document.createElement("img");
    avatarElement.className = "user-location-avatar";
    avatarElement.src = userAvatarUrl;
    avatarElement.alt = "";
    wrapper.appendChild(avatarElement);
    return wrapper;
  }

  const dotElement = document.createElement("div");
  dotElement.className = "user-location-accent-dot";
  wrapper.appendChild(dotElement);
  return wrapper;
}

function removeUserOverlayMarker() {
  if (!userOverlayMarker) {
    lastAppliedLocationUpdate = null;
    return;
  }
  userOverlayMarker.remove();
  userOverlayMarker = null;
  lastAppliedLocationUpdate = null;
}

function refreshUserOverlayMarker() {
  if (!lastAppliedLocationUpdate) {
    return;
  }
  const locationUpdate = lastAppliedLocationUpdate;
  const { longitude, latitude, heading } = locationUpdate;
  removeUserOverlayMarker();
  updateUserOverlay(longitude, latitude, heading);
  lastAppliedLocationUpdate = locationUpdate;
}

// oxlint-disable-next-line no-unused-vars -- Exposed as an Android WebView map bridge entry point.
function setUserAvatarUrl(url) {
  const normalizedAvatarUrl = normalizeUserAvatarUrl(url);
  if (userAvatarUrl === normalizedAvatarUrl) {
    return;
  }
  userAvatarUrl = normalizedAvatarUrl;
  refreshUserOverlayMarker();
}

function updateUserOverlayHeading(heading) {
  if (!userOverlayMarker) {
    return;
  }
  const element = userOverlayMarker.getElement();
  if (Number.isFinite(heading)) {
    element.style.setProperty("--user-heading", `${heading}deg`);
    element.classList.add("has-heading");
    return;
  }
  element.style.removeProperty("--user-heading");
  element.classList.remove("has-heading");
}

function updateUserOverlay(longitude, latitude, heading) {
  const hasLocation = Number.isFinite(longitude) && Number.isFinite(latitude);
  if (!hasLocation) {
    removeUserOverlayMarker();
    return;
  }

  const lngLat = [longitude, latitude];
  if (!userOverlayMarker) {
    const element = createUserOverlayElement();
    userOverlayMarker = new mapboxgl.Marker({
      element,
      anchor: "center",
    })
      .setLngLat(lngLat)
      .addTo(map);
  } else {
    userOverlayMarker.setLngLat(lngLat);
  }
  updateUserOverlayHeading(heading);
}

function normalizePositionTimestampMs(positionTimestampMs) {
  if (Number.isFinite(positionTimestampMs)) {
    return positionTimestampMs;
  }
  return Date.now();
}

function normalizeHeading(heading) {
  if (!Number.isFinite(heading)) {
    return null;
  }
  const normalized = heading % 360;
  return normalized < 0 ? normalized + 360 : normalized;
}

function estimateDistanceMeters(first, second) {
  const toRadians = (value) => (value * Math.PI) / 180;
  const latitudeDeltaRadians = toRadians(first.latitude - second.latitude);
  const longitudeDeltaRadians = toRadians(first.longitude - second.longitude);
  const averageLatitudeRadians = toRadians(
    (first.latitude + second.latitude) / 2
  );
  const earthRadiusMeters = 6371000;
  const x = longitudeDeltaRadians * Math.cos(averageLatitudeRadians);
  const y = latitudeDeltaRadians;
  return Math.hypot(x, y) * earthRadiusMeters;
}

function hasMeaningfulLocationDelta(next, previous) {
  const distanceMeters = estimateDistanceMeters(next, previous);
  if (distanceMeters >= minimumMeaningfulMoveMeters) {
    return true;
  }
  const previousHeading = previous.heading;
  const nextHeading = next.heading;
  if (!Number.isFinite(previousHeading) || !Number.isFinite(nextHeading)) {
    return false;
  }
  const headingDelta = Math.abs(nextHeading - previousHeading);
  const wrappedHeadingDelta = Math.min(headingDelta, 360 - headingDelta);
  return wrappedHeadingDelta >= minimumMeaningfulHeadingDelta;
}

function shouldApplyLocationUpdate(nextUpdate) {
  if (!lastAppliedLocationUpdate) {
    return true;
  }
  const latestUpdate = lastAppliedLocationUpdate;
  const updateIsStaleByTimestamp =
    nextUpdate.positionTimestampMs + staleLocationToleranceMs <
    latestUpdate.positionTimestampMs;
  if (updateIsStaleByTimestamp) {
    return false;
  }
  const updateHasLowerPriorityWithoutNewerTimestamp =
    nextUpdate.sourcePriority < latestUpdate.sourcePriority &&
    nextUpdate.positionTimestampMs <=
      latestUpdate.positionTimestampMs + staleLocationToleranceMs;
  if (updateHasLowerPriorityWithoutNewerTimestamp) {
    return false;
  }
  const hasMeaningfulDelta = hasMeaningfulLocationDelta(
    nextUpdate,
    latestUpdate
  );
  if (
    !hasMeaningfulDelta &&
    nextUpdate.sourcePriority <= latestUpdate.sourcePriority
  ) {
    return false;
  }
  return true;
}

function applyLocationUpdate({
  longitude,
  latitude,
  heading,
  source,
  sourcePriority,
  positionTimestampMs,
}) {
  if (!Number.isFinite(longitude) || !Number.isFinite(latitude)) {
    return;
  }
  const candidate = {
    longitude,
    latitude,
    heading: normalizeHeading(heading),
    source,
    sourcePriority,
    positionTimestampMs: normalizePositionTimestampMs(positionTimestampMs),
  };
  if (!shouldApplyLocationUpdate(candidate)) {
    return;
  }
  updateUserOverlay(candidate.longitude, candidate.latitude, candidate.heading);
  lastAppliedLocationUpdate = candidate;
}

function clearGeolocateControl() {
  if (geolocateLoadHandler) {
    map.off("load", geolocateLoadHandler);
    geolocateLoadHandler = null;
  }
  if (geolocateControl && geolocateEventHandler) {
    geolocateControl.off("geolocate", geolocateEventHandler);
    geolocateEventHandler = null;
  }
  if (geolocateControl && geolocateErrorHandler) {
    geolocateControl.off("error", geolocateErrorHandler);
    geolocateErrorHandler = null;
  }
  if (!geolocateControl) {
    removeUserOverlayMarker();
    hasReceivedTrackedLocation = false;
    return;
  }
  map.removeControl(geolocateControl);
  geolocateControl = null;
  hasReceivedTrackedLocation = false;
  removeUserOverlayMarker();
}

function setUserLocationEnabled(enabled) {
  if (!enabled) {
    clearGeolocateControl();
    return;
  }

  if (geolocateControl) {
    return;
  }

  if (navigator.geolocation && !initialFastLocationRequestInFlight) {
    initialFastLocationRequestInFlight = true;
    navigator.geolocation.getCurrentPosition(
      (position) => {
        initialFastLocationRequestInFlight = false;
        const coords = position && position.coords;
        if (!coords) {
          return;
        }
        applyLocationUpdate({
          longitude: coords.longitude,
          latitude: coords.latitude,
          heading: coords.heading,
          source: "fast_initial",
          sourcePriority: locationSourcePriority.fastInitial,
          positionTimestampMs: position?.timestamp,
        });
      },
      (_error) => {
        initialFastLocationRequestInFlight = false;
      },
      initialFastLocationOptions
    );
  }

  geolocateControl = new mapboxgl.GeolocateControl({
    trackUserLocation: true,
    // We render our own user-location marker/heading overlay, so keep Mapbox's
    // tracking behavior but disable its built-in puck UI to avoid duplicates.
    showUserLocation: false,
    showUserHeading: false,
    showButton: false,
    followUserLocation: false,
    positionOptions: trackedLocationOptions,
  });
  map.addControl(geolocateControl);

  geolocateEventHandler = (event) => {
    const coords = event && event.coords;
    if (!coords) {
      return;
    }
    hasReceivedTrackedLocation = true;
    applyLocationUpdate({
      longitude: coords.longitude,
      latitude: coords.latitude,
      heading: coords.heading,
      source: "tracked",
      sourcePriority: locationSourcePriority.tracked,
      positionTimestampMs: event?.timestamp,
    });
  };
  geolocateErrorHandler = (error) => {
    if (error?.code === geolocationPermissionDeniedCode) {
      removeUserOverlayMarker();
    }
  };
  geolocateControl.on("geolocate", geolocateEventHandler);
  geolocateControl.on("error", geolocateErrorHandler);

  const triggerGeolocate = () => {
    if (!geolocateControl) {
      return;
    }
    geolocateControl.trigger();
  };

  // Trigger immediately to avoid waiting for map style load before first location fix.
  triggerGeolocate();

  if (map.isStyleLoaded && map.isStyleLoaded()) {
    return;
  }

  geolocateLoadHandler = () => {
    if (hasReceivedTrackedLocation) {
      if (geolocateLoadHandler) {
        map.off("load", geolocateLoadHandler);
        geolocateLoadHandler = null;
      }
      return;
    }
    triggerGeolocate();
    if (geolocateLoadHandler) {
      map.off("load", geolocateLoadHandler);
      geolocateLoadHandler = null;
    }
  };
  map.on("load", geolocateLoadHandler);
}

function findMarkerHit(point) {
  if (!currentMarkers.length) {
    return null;
  }
  const canvasRect = map.getCanvas().getBoundingClientRect();
  const clickX = canvasRect.left + point.x;
  const clickY = canvasRect.top + point.y;
  const candidates = currentMarkers
    .map((marker) => {
      const markerRect = marker.element.getBoundingClientRect();
      const labelRect = marker.element
        .querySelector(".marker-label")
        ?.getBoundingClientRect();
      const left =
        Math.min(markerRect.left, labelRect?.left ?? markerRect.left) -
        markerHitPadding;
      const right =
        Math.max(markerRect.right, labelRect?.right ?? markerRect.right) +
        markerHitPadding;
      const top =
        Math.min(markerRect.top, labelRect?.top ?? markerRect.top) -
        markerHitPadding;
      const bottom =
        Math.max(markerRect.bottom, labelRect?.bottom ?? markerRect.bottom) +
        markerHitPadding;
      if (clickX < left || clickX > right || clickY < top || clickY > bottom) {
        return null;
      }
      const centerX = markerRect.left + markerRect.width / 2;
      const centerY = markerRect.top + markerRect.height / 2;
      const dx = clickX - centerX;
      const dy = clickY - centerY;
      return {
        marker,
        distance: Math.hypot(dx, dy),
      };
    })
    .filter(Boolean);

  if (!candidates.length) {
    return null;
  }

  candidates.sort((a, b) => {
    if (a.distance !== b.distance) {
      return a.distance - b.distance;
    }
    if (a.marker.selected === b.marker.selected) {
      return 0;
    }
    return a.marker.selected ? 1 : -1;
  });

  return candidates[0].marker;
}

map.on("dragstart", (event) => {
  if (!event || !event.originalEvent) {
    return;
  }
  lastMoveWasUser = true;
});

map.on("zoomstart", (event) => {
  if (!event || !event.originalEvent) {
    return;
  }
  lastMoveWasUser = true;
});

map.on("rotatestart", (event) => {
  if (!event || !event.originalEvent) {
    return;
  }
  lastMoveWasUser = true;
});

map.on("pitchstart", (event) => {
  if (!event || !event.originalEvent) {
    return;
  }
  lastMoveWasUser = true;
});

map.on("movestart", (event) => {
  // `originalEvent` can be missing in WebView contexts; preserve any prior user signal.
  const shouldSetUserMove = Boolean(event.originalEvent);
  lastMoveWasUser = lastMoveWasUser || shouldSetUserMove;
});

map.on("moveend", () => {
  if (!mapHasSettled) {
    lastMoveWasUser = false;
    return;
  }
  notifyViewportChanged(lastMoveWasUser);
  lastMoveWasUser = false;
});

map.on("click", (event) => {
  const hitMarker = findMarkerHit(event.point);
  if (!hitMarker) {
    if (window.AndroidInterface && window.AndroidInterface.notifyMapClick) {
      window.AndroidInterface.notifyMapClick();
    }
    return;
  }
  if (window.AndroidInterface && window.AndroidInterface.notifyMarkerSelect) {
    window.AndroidInterface.notifyMarkerSelect(hitMarker.id);
  }
});

const markers = JSON.parse(decodeURIComponent(params.get("markers")));
updateMarkers(markers);

const showUserLocation = params.get("showUserLocation") === "true";
setUserLocationEnabled(showUserLocation);

// If no location provided, fit the map to the markers
if (!latitude || !longitude) {
  fitMarkersToBounds(markers, false);
}

map.once("idle", () => {
  // Mapbox can emit move events during initial layout/camera adjustments. Wait until
  // the first idle to consider the map settled, then seed a non-user viewport update.
  mapHasSettled = true;
  lastMoveWasUser = false;
  if (
    window.AndroidInterface &&
    window.AndroidInterface.notifyInitialContentsLoaded
  ) {
    window.AndroidInterface.notifyInitialContentsLoaded();
  }
  notifyViewportChanged(false);
});
