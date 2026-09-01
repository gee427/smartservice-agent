"""
Day 1: 单 Agent + 工具调用（LangGraph 新版 API）
目标：理解 ReAct 模式，观察 Agent 的思考过程

⚠️ 注意：LangChain v1.3+ 已移除旧的 initialize_agent，
改用 LangGraph 的 create_react_agent，本文件已更新。
"""
from langchain_openai import ChatOpenAI
from langchain_core.tools import tool
from langgraph.prebuilt import create_react_agent
from langchain_core.messages import HumanMessage

# ========== 配置 LM Studio 本地模型 ==========
llm = ChatOpenAI(
    model="local-model",
    api_key="not-needed",
    base_url="http://localhost:1234/v1",
    temperature=0.7
)

# ========== 定义工具（用 @tool 装饰器）==========
@tool
def get_weather(city: str) -> str:
    """查询指定城市的天气情况，输入城市名如'北京'、'上海'"""
    weather_data = {
        "北京": "晴，25°C，北风2级，空气质量优",
        "上海": "多云，28°C，东南风3级",
        "广州": "小雨，30°C，南风2级",
        "深圳": "晴，31°C，微风",
        "杭州": "阴，26°C，东北风2级"
    }
    return weather_data.get(city, f"抱歉，暂无{city}的天气数据")

@tool
def calculator(expression: str) -> str:
    """进行数学计算，输入如'2+3*4'、'(100-20)/4'"""
    try:
        allowed = set("0123456789+-*/(). ")
        if all(c in allowed for c in expression):
            result = eval(expression)
            return f"计算结果：{result}"
        return "表达式包含非法字符"
    except Exception as e:
        return f"计算错误：{str(e)}"

tools = [get_weather, calculator]

# ========== 初始化 ReAct Agent（LangGraph 方式）==========
agent = create_react_agent(
    model=llm,
    tools=tools,
)


# ========== 封装一个流式运行函数，可以看到 Agent 思考过程 ==========
def run_agent(query: str) -> str:
    """
    运行 Agent 并流式输出每步思考过程。
    相当于旧的 verbose=True。
    """
    print(f"\n📝 用户提问：{query}")
    print("-" * 60)

    final_answer = ""
    for event in agent.stream({"messages": [HumanMessage(content=query)]}):
        for node_name, output in event.items():
            if node_name == "agent":
                msg = output["messages"][-1]
                if msg.tool_calls:
                    for tc in msg.tool_calls:
                        print(f"  🤔 思考 → 调用 {tc['name']}({tc['args']})")
                elif msg.content:
                    # Agent 给出的最终回答
                    final_answer = msg.content
            elif node_name == "tools":
                for msg in output["messages"]:
                    if hasattr(msg, "content") and msg.content:
                        print(f"  🔧 工具返回：{msg.content}")

    print("=" * 60)
    print(f"【最终结果】{final_answer}")
    print()
    return final_answer


# ========== 测试用例 ==========
if __name__ == "__main__":
    run_agent("北京今天天气怎么样？适合穿什么衣服？")

    run_agent("帮我算一下 15 * 23 + 8 等于多少？")

    run_agent("北京和上海哪个城市今天更热？温度差多少？")
