import matplotlib.pyplot as plt
import networkx as nx
import os

def visualize_FPGrowth_rules(input_file, algo):
    #Check if the input file exists
    if not os.path.exists(input_file):
        print(f"File {input_file} does not exist.")
        return
    G=nx.DiGraph()
    labels={"1":"LONG_LEN", 
            "2":"MANY_EQUAL",
            "3":"HAS_WHERE", 
            "4":"HAS_SELECT",
            "5":"HAS_UNION",
            "6":"MANY_QUOTES",
            "7":"HAS_COMMENT",
            "8":"HIGH_OR",
            "33":"MANY_WHERE",
            "44":"MANY_SELECT",
            "55":"MANY_UNION",
            "100":"BENIGN",
            "101":"MALICIOUS"}
    with open(input_file, "r") as f:
        for line in f:
            if "==>" in line and "#SUP:" in line:
                parts=line.split("==>")
                lhs_items=parts[0].strip().split()
                rhs_parts=parts[1].strip().split("#SUP:")
                rhs_items=rhs_parts[0].strip().split()
                support=int(rhs_parts[1].split("#CONF:")[0])
                # Construct arrows from LHS points to RHS 
                for u in lhs_items:
                    for v in rhs_items:
                        u_label = labels.get(u, u)
                        v_label = labels.get(v, v)
                        # If edge already exists, update weight; otherwise add new edge
                        if G.has_edge(u_label, v_label):
                            G[u_label][v_label]['weight'] += support
                        else:
                            G.add_edge(u_label, v_label, weight=support)
    plt.figure(figsize=(12, 12))
    pos=nx.spring_layout(G, k=0.3, seed=42)
    max_w=max([G[u][v]['weight'] for u, v in G.edges()]) if G.edges() else 1
    weights=[(G[u][v]['weight']/max_w)*5 for u, v in G.edges()]
    nx.draw(G, pos, with_labels=True, width=weights, node_color='orange', 
            node_size=2000, font_size=20, font_weight='bold', edge_color='gray', arrowsize=30, connectionstyle='arc3,rad=0.1')
    
    plt.title("SQL Injection Association Rule Mining: " + algo)
    plt.savefig(f"{algo}_rule_mining.png")
    print("Visualization saved as " + algo + "_rule_mining.png")
    plt.show()

visualize_FPGrowth=True

if visualize_FPGrowth:
    visualize_FPGrowth_rules("../data/processed/Stage3/Version1/FPGrowth_output_minSup_0.05_minConf_0.6.txt", "FP-Growth(minSup=0.05, minConf=0.6)")
    visualize_FPGrowth_rules("../data/processed/Stage3/Version1/FPGrowth_output_minSup_0.05_minConf_0.8.txt", "FP-Growth(minSup=0.05, minConf=0.8)")
    visualize_FPGrowth_rules("../data/processed/Stage3/Version1/FPGrowth_output_minSup_0.1_minConf_0.6.txt", "FP-Growth(minSup=0.1, minConf=0.6)")
    visualize_FPGrowth_rules("../data/processed/Stage3/Version1/FPGrowth_output_minSup_0.1_minConf_0.8.txt", "FP-Growth(minSup=0.1, minConf=0.8)")