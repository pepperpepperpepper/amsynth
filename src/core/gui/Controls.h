/*
 *  Controls.h
 *
 *  Copyright (c) 2023 Nick Dowell
 *
 *  This file is part of amsynth.
 *
 *  amsynth is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  amsynth is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with amsynth.  If not, see <http://www.gnu.org/licenses/>.
 */

#pragma once

#include "LayoutDescription.h"
#include "core/synth/Parameter.h"

#include "juce_gui_basics/juce_gui_basics.h"

////////////////////////////////////////////////////////////////////////////////

class Control : public juce::Component, protected Parameter::Observer {
public:
	Control(Parameter &p, juce::Image image, const LayoutDescription::Resource &r);
	~Control();

	void repaintIfNeeded();

	Parameter &parameter;

	static thread_local bool isMainThread;

protected:
	virtual void leftMouseDown(const juce::MouseEvent &event) = 0;
	void mouseDown(const juce::MouseEvent &event) final;
	void mouseDoubleClick(const juce::MouseEvent &event) final;
	void paint(juce::Graphics &g) override;
	void parameterDidChange(const Parameter &) override;

private:
	std::atomic_int frame_;
	juce::Image image_;
	int width_, height_;
	int frames_;
	std::atomic_bool needsRepaint_;
};

////////////////////////////////////////////////////////////////////////////////

class Button : public Control {
public:
	Button(Parameter &parameter, juce::Image image, const LayoutDescription::Resource &r)
	: Control(parameter, std::move(image), r) {}

private:
	class AccessibilityHandler : public juce::AccessibilityHandler
	{
	public:
		explicit AccessibilityHandler(Button &ctrl)
		: juce::AccessibilityHandler(ctrl, juce::AccessibilityRole::toggleButton, getAccessibilityActions(ctrl), { std::make_unique<ValueInterface>(ctrl) })
		, control(ctrl)
		{}

		juce::AccessibleState getCurrentState() const override {
			auto state = juce::AccessibilityHandler::getCurrentState().withCheckable();
			return control.parameter.getNormalisedValue() > 0.f ? state.withChecked() : state;
		}

		juce::String getTitle() const override { return control.parameter.getName(); }
		juce::String getHelp() const override { return ""; }

	private:
		class ValueInterface : public juce::AccessibilityTextValueInterface
		{
		public:
			explicit ValueInterface(Button &ctrl) : control(ctrl) {}

			juce::String getCurrentValueAsString() const override { return control.parameter.getNormalisedValue() > 0.f ? "On" : "Off"; }
			bool isReadOnly() const override { return true; }
			void setValueAsString(const juce::String &) override {}

			Button &control;
		};

		static juce::AccessibilityActions getAccessibilityActions(Button &control) {
			return juce::AccessibilityActions().addAction(juce::AccessibilityActionType::toggle, [&control] { control.toggle(); });
		}

		Button &control;
	};

	std::unique_ptr<juce::AccessibilityHandler> createAccessibilityHandler() override {
		return std::make_unique<AccessibilityHandler>(*this);
	}

	void leftMouseDown(const juce::MouseEvent &) override {
		toggle();
	}

	void toggle() {
		parameter.beginEdit();
		parameter.setNormalisedValue(parameter.getNormalisedValue() > 0.f ? 0.f : 1.f);
		parameter.endEdit();
	}
};

////////////////////////////////////////////////////////////////////////////////

class Knob : public Control {
public:
	class Label : public juce::Component {
	public:
		Label(juce::Component *parent);

		int yInset {0};

		void show(juce::Component *control, juce::String text);
		void hide();

	private:
		void paint(juce::Graphics &graphics);

		juce::Component *parent_ {nullptr};
		juce::Component *control_ {nullptr};
		juce::String text_;
	};

	Knob(Parameter &parameter, juce::Image image, const LayoutDescription::Resource &r, Label *label);

private:
	void mouseEnter(const juce::MouseEvent &event) override;
	void mouseExit(const juce::MouseEvent &event) override;
	void mouseUp(const juce::MouseEvent &event) override;
	void leftMouseDown(const juce::MouseEvent &event) override;
	void mouseDrag(const juce::MouseEvent &event) override;
	void mouseWheelMove(const juce::MouseEvent &event, const juce::MouseWheelDetails &wheel) override;

	juce::String getLabelText();

	float referenceVal_ {0.f};
	int referenceY_ {0};
	Label *label_;
};

////////////////////////////////////////////////////////////////////////////////

class Popup : public Control {
public:
	Popup(Parameter &parameter, juce::Image image, const LayoutDescription::Resource &r)
	: Control(parameter, std::move(image), r) {
		setAccessible(true);
	}

private:
	class AccessibilityHandler : public juce::AccessibilityHandler
	{
	public:
		explicit AccessibilityHandler(Popup &ctrl)
		: juce::AccessibilityHandler(ctrl, juce::AccessibilityRole::comboBox, getAccessibilityActions(ctrl), { std::make_unique<ValueInterface>(ctrl) })
		, control(ctrl)
		{}

		juce::AccessibleState getCurrentState() const override {
			return juce::AccessibilityHandler::getCurrentState().withExpandable().withCollapsed();
		}

		juce::String getTitle() const override { return control.parameter.getName(); }
		juce::String getHelp() const override { return ""; }

	private:
		class ValueInterface : public juce::AccessibilityTextValueInterface
		{
		public:
			explicit ValueInterface(Popup &ctrl) : control(ctrl) {}

			juce::String getCurrentValueAsString() const override {
				const char **strings = parameter_get_value_strings(control.parameter.getId());
				return strings[(int)std::round(control.parameter.getValue())];
			}

			bool isReadOnly() const override { return true; }
			void setValueAsString(const juce::String &) override {}

			Popup &control;
		};

		static juce::AccessibilityActions getAccessibilityActions (Popup &popup) {
			return juce::AccessibilityActions()
				.addAction (juce::AccessibilityActionType::press,    [&popup] { popup.showPopup(); })
				.addAction (juce::AccessibilityActionType::showMenu, [&popup] { popup.showPopup(); });
		}

		Popup &control;
	};

	std::unique_ptr<juce::AccessibilityHandler> createAccessibilityHandler() override {
		return std::make_unique<AccessibilityHandler>(*this);
	}

	void leftMouseDown(const juce::MouseEvent &) override {
		showPopup();
	}

	void showPopup() {
		auto strings = parameter_get_value_strings(parameter.getId());
		auto menu = juce::PopupMenu();
		for (int i = 0; i <= parameter.getSteps(); i++) {
			auto value = parameter.getMin() + parameter.getStep() * float(i);
			auto isTicked = parameter.getValue() == value;
			auto itemText = juce::String(juce::CharPointer_UTF8(strings[i]));
			menu.addItem(itemText, true, isTicked, [&parameter = parameter, value] {
				parameter.beginEdit();
				parameter.setValue(value);
				parameter.endEdit();
			});
		}
		menu.showMenuAsync(juce::PopupMenu::Options().withTargetComponent(this));
	}
};
