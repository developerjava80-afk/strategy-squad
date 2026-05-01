# Trading Platform UI Prototype

Standalone UI prototype for the next Strategy Squad trading platform layout.

This folder is intentionally separate from `ui/scenario-research`. It does not call backend APIs and does not modify the current application. The goal is to review product flow, information architecture, and screen composition before integration.

## Screens

- `index.html` - operating dashboard
- `strategy-lab.html` - parameter-driven strategy scoring workspace
- `orders.html` - order placement and execution tracking
- `testing-analytics.html` - replay, testing, and performance analytics

## How to view

Open `index.html` directly in a browser.

The prototype uses static sample data in `prototype.js`. Strategy Lab controls recalculate visible scores locally so the screen behavior can be reviewed without a running backend.
