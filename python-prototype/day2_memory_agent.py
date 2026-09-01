"""
Day 2: 加入对话记忆
目标：让 Agent 记住多轮对话上下文
"""
from langchain_openai import ChatOpenAI
from langchain_core.tools import tool
from langgraph.prebuilt import create_react_agent
from langchain_core.messages import HumanMessage, AIMessage

llm = ChatOpenAI(
    model="local-model",
    api_key="not-needed",
    base_url="http://localhost:1234/v1",
    temperature=0.7
)

@tool
def get_weather(city: str) -> str:
    """查询指定城市的天气情况，输入城市名如'北京'、'上海'"""
    weather_data = {"北京": "晴，25°C", "上海": "多云，28°C", "广州": "小雨，30°C"}
    return weather_data.get(city, f"暂无{city}数据")

tools = [get_weather]

# ========== 创建 Agent ==========
agent = create_react_agent(model=llm, tools=tools)

# ========== 手动管理对话历史（代替旧的 ConversationBufferWindowMemory）==========
conversation_history = []
MAX_HISTORY = 10  # 最多保留5轮（10条消息，1轮=用户+助手各1条）

def run_agent(user_input: str) -> str:
    """带记忆的对话"""
    # 构建消息列表 = 历史消息 + 新输入
    messages = conversation_history + [HumanMessage(content=user_input)]

    print(f"\n👤 用户: {user_input}")
    print("-" * 40)

    final_answer = ""
    for event in agent.stream({"messages": messages}):
        for node_name, output in event.items():
            if node_name == "agent":
                msg = output["messages"][-1]
                if msg.tool_calls:
                    for tc in msg.tool_calls:
                        print(f"  🤔 调用 {tc['name']}({tc['args']})")
                elif msg.content:
                    final_answer = msg.content
            elif node_name == "tools":
                for msg in output["messages"]:
                    if hasattr(msg, "content") and msg.content:
                        print(f"  🔧 结果：{msg.content}")

    # 将本轮对话加入历史
    conversation_history.append(HumanMessage(content=user_input))
    conversation_history.append(AIMessage(content=final_answer))

    # 裁掉最早的超过上限的消息
    if len(conversation_history) > MAX_HISTORY:
        conversation_history[: len(conversation_history) - MAX_HISTORY] = []

    print(f"🤖 {final_answer}")
    return final_answer


if __name__ == "__main__":
    print("=" * 60)
    print("多轮对话测试（Agent 会记住你是谁）")
    print("=" * 60)

    # 第一轮：自我介绍
    run_agent("我叫张三，帮我查一下北京天气")

    # 第二轮：测试记忆
    run_agent("我叫什么名字？")

    # 第三轮：结合工具和记忆
    run_agent("那我明天去北京出差，北京天气适合准备什么衣服？")

    # 第四轮：上下文关联
    run_agent("对了，上海天气怎么样？和我上次查的北京比一下")

    print("\n" + "=" * 60)
    print(f"当前记忆中共 {len(conversation_history)} 条消息")
    print("=" * 60)
