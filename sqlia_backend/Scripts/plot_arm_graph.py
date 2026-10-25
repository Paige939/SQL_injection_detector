"""Generate static FP-Growth ARM directed graphs for the Flutter UI.

Run from sqlia_app:
    python Scripts/plot_arm_graph.py

Dependencies:
    python -m pip install matplotlib networkx
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path
from matplotlib.lines import Line2D

try:
    import matplotlib.pyplot as plt
    import networkx as nx
except ImportError as error:
    raise SystemExit(
        "Missing plotting dependency. Install with: "
        "python -m pip install matplotlib networkx"
    ) from error

ITEM_NAMES = {
    1: "LONG_LEN",
    2: "HIGH_SYMBOL",
    3: "ZERO_COMPARISON",
    30: "LOW_COMPARISON",
    33: "HIGH_COMPARISON",
    40: "COMPLEXITY_0",
    41: "COMPLEXITY_1",
    42: "COMPLEXITY_2",
    43: "COMPLEXITY_3",
    5: "ZERO_FUNCTION",
    50: "LOW_FUNCTION",
    55: "HIGH_FUNCTION",
    6: "HIGH_DISCONTINUOUS",
}

RULE_PATTERN = re.compile(
    r"^(?P<left>.*?)\s*==>\s*(?P<right>.*?)\s+"
    r"#SUP:\s*(?P<support>\d+)\s+#CONF:\s*(?P<confidence>[0-9.]+)\s*$"
)


def parse_rules(path: Path) -> list[tuple[list[int], list[int], int, float]]:
    """Parse SPMF rules from one FP-Growth output file."""
    rules = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        match = RULE_PATTERN.match(line.strip())
        if not match:
            continue
        left = [int(item) for item in match.group("left").split()]
        right = [int(item) for item in match.group("right").split()]
        rules.append(
            (left, right, int(match.group("support")), float(match.group("confidence")))
        )
    return rules


def item_name(item: int) -> str:
    """Convert the numeric SPMF item into the feature label shown in the graph."""
    return ITEM_NAMES.get(item, f"ITEM_{item}")


def draw_graph(rules: list[tuple[list[int], list[int], int, float]], title: str, output: Path) -> None:
    """Draw nodes and directed confidence-weighted edges into a PNG file."""
    graph = nx.DiGraph()
    for left, right, _, confidence in rules[:40]:
        for source in left:
            for target in right:
                graph.add_edge(source, target, confidence=confidence)

    if not graph.nodes:
        print(f"Skip empty rule file: {title}")
        return

    figure, axis = plt.subplots(figsize=(16, 10), dpi=160)
    positions = nx.spring_layout(graph, seed=42, k=1.4)
    # Use visibly separated line classes so confidence differences are easy to compare.
    edge_classes = {"low": [], "medium": [], "high": []}
    edge_colors = {"low": "#9aa7b2", "medium": "#e67e22", "high": "#c0392b"}
    edge_widths = {"low": 2.0, "medium": 5.0, "high": 9.0}

    for edge in graph.edges:
        confidence = graph.edges[edge]["confidence"]
        if confidence < 0.75:
            edge_classes["low"].append(edge)
        elif confidence < 0.90:
            edge_classes["medium"].append(edge)
        else:
            edge_classes["high"].append(edge)

    nx.draw_networkx_nodes(
        graph,
        positions,
        node_color="#d9ecff",
        edgecolors="#145da0",
        node_size=2600,
        linewidths=1.8,
        ax=axis,
    )
    # Draw each confidence class separately to make both width and color distinct.
    for level in ("low", "medium", "high"):
        nx.draw_networkx_edges(
            graph,
            positions,
            edgelist=edge_classes[level],
            edge_color=edge_colors[level],
            width=edge_widths[level],
            arrows=True,
            arrowsize=30,
            arrowstyle="-|>",
            node_size=2600,
            min_source_margin=12,
            min_target_margin=18,
            connectionstyle="arc3,rad=0.12",
            ax=axis,
        )

    # Show the numeric confidence beside each directed edge.
    edge_labels = {
        edge: f"{graph.edges[edge]['confidence']:.2f}"
        for edge in graph.edges
    }
    nx.draw_networkx_edge_labels(
        graph,
        positions,
        edge_labels=edge_labels,
        font_size=8,
        label_pos=0.55,
        rotate=False,
        bbox={"alpha": 0.8, "color": "white", "pad": 0.2},
        ax=axis,
    )
    nx.draw_networkx_labels(
        graph,
        positions,
        labels={item: item_name(item) for item in graph.nodes},
        font_size=9,
        font_weight="bold",
        ax=axis,
    )

    legend = [
        Line2D([0], [0], color=edge_colors["low"], lw=edge_widths["low"], label="Low confidence (< 0.75)"),
        Line2D([0], [0], color=edge_colors["medium"], lw=edge_widths["medium"], label="Medium confidence (0.75–0.89)"),
        Line2D([0], [0], color=edge_colors["high"], lw=edge_widths["high"], label="High confidence (>= 0.90)"),
    ]
    axis.legend(handles=legend, loc="lower left", frameon=True)
    axis.set_title(
        f"{title}\nArrow direction: antecedent → consequent; label: confidence",
        fontsize=16,
        weight="bold",
    )
    axis.axis("off")
    figure.tight_layout()
    output.parent.mkdir(parents=True, exist_ok=True)
    figure.savefig(output, bbox_inches="tight", facecolor="white")
    plt.close(figure)
    print(f"Wrote {output}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--project-root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="sqlia_app project root",
    )
    args = parser.parse_args()

    input_root = args.project_root / "data" / "processed" / "Stage4" / "Version1"
    output_root = args.project_root.parent / "sqlia_web" / "assets" / "arm_graphs"
    files = {
        "stage4-v1-fpgrowth-01-06": "FPGrowth_output_minSup_0.1_minConf_0.6.txt",
        "stage4-v1-fpgrowth-01-08": "FPGrowth_output_minSup_0.1_minConf_0.8.txt",
        "stage4-v1-fpgrowth-005-06": "FPGrowth_output_minSup_0.05_minConf_0.6.txt",
        "stage4-v1-fpgrowth-005-08": "FPGrowth_output_minSup_0.05_minConf_0.8.txt",
    }

    for rule_set_id, filename in files.items():
        source = input_root / filename
        if not source.exists():
            print(f"Skip missing rule file: {source}")
            continue
        rules = parse_rules(source)
        draw_graph(
            rules,
            rule_set_id,
            output_root / f"{rule_set_id}.png",
        )


if __name__ == "__main__":
    main()
